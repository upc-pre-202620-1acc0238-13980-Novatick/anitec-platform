# Etapa 13B — Elegibilidad e idioma de correo

Decisiones H/I aprobadas del MVP. Base: develop con PR #13 integrada. El Team Leader aprobó
por separado la ampliación de contrato siguiente **antes de implementarla**. Sin esquema nuevo,
migraciones, preferencias de cuenta, endpoints, proveedores o flags productivos nuevos.

## Elegibilidad

- Enviar una invitación exige destinatario con rol VETERINARIO, status ACTIVA y verifiedAt presente.
  Cuentas desconocidas, de otro rol, pendientes o suspendidas comparten HTTP 422,
  `RECIPIENT_NOT_REGISTERED` y un mensaje genérico. El código existente se conserva por compatibilidad:
  Android debe presentarlo como destinatario no disponible, sin inferir su estado privado.
- Al ACCEPT se comprueba primero que la invitación corresponde al actor y sigue pendiente/no vencida;
  se relee la cuenta y se exige otra vez rol/estado/verificación, antes de ocupar cupo o activar vínculo.
  Si esa revalidación de servicio falla: 409 `INVALID_STATE_TRANSITION`, mensaje genérico sin estado privado.
- En HTTP una cuenta que se volvió suspendida/no verificada normalmente ya recibirá 401 de JWT/sesiones
  antes del controlador; un rol no veterinario sigue sin poder responder. Eso no reemplaza el control del servicio.
- REJECT, vencimientos, duplicados, límites de plan y revocación conservan sus reglas. Un intento rechazado
  no consume cupo ni cambia la invitación. Recuperar posteriormente elegibilidad permite aceptar mientras
  la invitación siga vigente; una sesión revocada requiere nuevo login, no se reactiva por cambiar el estado.
- La comprobación observa el estado actual en la transacción. No se añadieron bloqueos entre contextos
  ni se promete serialización con cambios de cuenta que ocurran después de la comprobación/in-flight.

## Accept-Language para el correo de la operación

Cabecera opcional en:

| Método y ruta | Efecto |
| --- | --- |
| POST /api/v1/auth/register | Idioma de la emisión inicial de verificación |
| POST /api/v1/auth/resend-code | Idioma del nuevo código solo con credenciales/cuota autorizadas |
| POST /api/v1/auth/password-recovery/request | Idioma capturado para la solicitud antes de su cola asíncrona |
| POST /api/v1/invitations | Idioma solicitado por el emisor para el correo dirigido al veterinario |

No cambia ningún DTO de entrada/salida, código HTTP válido ni estructura de error. La cabecera selecciona
**correo**, no idioma de la respuesta JSON. No se añade Content-Language a una respuesta que continúa en español.
No persiste una preferencia: cada nueva operación debe enviar la cabecera si quiere inglés.

- Idiomas soportados: es y en. Variantes como en-US/es-PE se resuelven al idioma base por lookup de etiquetas.
- Se respetan las preferencias ponderadas válidas; por ejemplo `es;q=0.3,en;q=0.9` selecciona inglés.
- Ausente, vacía, malformada, sin idioma compatible o mayor de512 caracteres: español. Una selección sin
  coincidencia, incluso todos los pesos cero o solo wildcard, usa ese fallback compatible; no es una negociación406.
- No se registran cabeceras crudas ni excepciones de su parser. El resultado interno es solo un enum es/en.
- En invitación el emisor elige explícitamente el idioma de esa comunicación. No se deduce idioma del correo,
  DNI, localidad o cuenta del veterinario. Si el emisor omite la cabecera, español.

## Asincronía, persistencia y seguridad

El controlador resuelve una preferencia inmutable y la pasa explícitamente al servicio. Recuperación captura
el valor en el trabajo de intake; no depende de LocaleContextHolder, ThreadLocal o del hilo HTTP para procesarlo.
La transacción que confirma emisión/outbox genera subject/html/text definitivos con las plantillas existentes.
Ese JSON se cifra como antes (AES-256-GCM, mismos AAD y restricciones); no se añade una columna de idioma.

Los reintentos/reclamaciones/reinicio leen el payload almacenado: conservan idioma, código, expiración,
idempotency key y bytes. Intentar preparar de nuevo la misma emisión con otra preferencia no modifica
el trabajo. Un reenvío autorizado es una emisión nueva y puede seleccionar otro idioma; los trabajos previos
quedan sujetos a la inelegibilidad/cancelación existente. No cambia cupos, HMAC ni vigencia por un reintento.

Las plantillas de verificación/recuperación mantienen HTML y texto plano en ambos idiomas. Las invitaciones
conservan su transporte directo de texto, ahora con asunto/cuerpo es/en: no se incorporan al outbox ni se
promete nueva durabilidad. SENT solo significa aceptación del proveedor; no entrega al buzón. La rama de error
de invitación registra una categoría fija, sin destinatario ni mensaje bruto de excepción.

Los overloads internos antiguos continúan seleccionando español. Los adaptadores de tests heredados
siguen compatibles; el outbox productivo implementa explícitamente los overloads con idioma.

## Operación y límites

No se activó Resend/Password Recovery en Render ni se configuró una clave real. Las pruebas con modo resend
y recuperación habilitada usan exclusivamente servidor HTTP localhost, claves sintéticas y PostgreSQL desechable.
La cabecera no habilita un servicio apagado, no autentica una cuenta ni evita cuotas o barrera de staging.
No se modifican HMAC, cifrado, sesiones, controles anti-enumeración ni V1–V18.

Stripe sigue en etapa14 y documentación/cierre global en etapa15. No se internacionalizan notificaciones,
rutas, JSON, logs o catálogos en este incremento. Merge y despliegue requieren las autorizaciones correspondientes.
