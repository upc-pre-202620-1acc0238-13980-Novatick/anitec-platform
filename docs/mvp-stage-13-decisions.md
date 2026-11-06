# ANITEC — Decisiones ratificadas del MVP, etapa 13

Fecha de aprobación: **2026-11-05, America/Lima**. Autoridad: Team Leader de ANITEC.
Base de 13A: develop `fcee9aed01cc2642e38751d5412b299a17db9fd3` (12 PR integradas).
Estas decisiones versionan el alcance aprobado; no sustituyen la trazabilidad final de historias de la etapa 15.

## Decisiones A–I

| ID | Decisión aprobada | Contrato/impacto |
| --- | --- | --- |
| A | El ganadero recibe avisos autosuficientes de visitas; la agenda pertenece al veterinario. | No crear agenda ganadero ni remitirlo a una ruta inaccesible. |
| B | Notificaciones como bandeja informativa. | Sin deep-links, targetId ni nuevos endpoints de navegación. |
| C | photoUrl de animal es opcional. | Retornar la URL almacenada al consultar para editar; sin multipart, almacenamiento propio o CDN. |
| D | Crear/listar/consultar fincas, programar visitas y controles. | Edición de fincas y reprogramación de citas fuera del MVP. |
| E | Perfil común actual para ganadero/veterinario. | Sin especialidad, colegiatura o zonas obligatorias; el rol no certifica habilitación profesional. |
| F | location de finca es texto opcional. | Android muestra «Ubicación no registrada» si falta; no confundir con domicilio del usuario ni agregar UBIGEO/coordenadas. |
| G | Bandeja in-app forma parte del MVP. | FCM real se pospone; no afirmar entrega push Android por un adaptador de consola. |
| H | Español e inglés en comunicaciones de correo del alcance aprobado. | Android localiza UI/códigos; backend selecciona es/en por Accept-Language opcional, español por defecto, propagando idioma al trabajo asíncrono. No traducir rutas, JSON o logs ni asumir preferencia persistida. Implementado en 13B. |
| I | Invitar únicamente a veterinarios activos y con correo verificado. | Revalidar al enviar y aceptar sin revelar estados privados innecesariamente. Implementado en 13B. |

## División de la etapa

**13A:** DTO individual de animales, avisos informativos, elementos null en complementos clínicos,
pruebas HTTP de operaciones existentes y este documento. No requiere cambios de esquema.

**13B:** elegibilidad de veterinarios e idioma real de correos, iniciada después de integrar 13A.
El Team Leader aprobó explícitamente Accept-Language opcional para registro, reenvío, solicitud de
recuperación e invitación; en invitaciones decide el emisor, sin preferencias persistidas del destinatario.
No necesita migración. La ratificación de H/I no significaba que 13A ya implementara esos controles.
Ver [contratos de 13B](linking-eligibility-email-i18n.md).

**Etapa 14:** Simulated Payment Lifecycle & Subscription Stabilization. La reducción de alcance aprobada
el 2026-11-06 sustituye Stripe por el simulador académico existente. Cada extensión requiere una nueva
operación ficticia aprobada; no hay cobros ni proveedor financiero. Stripe, Checkout, webhooks y Google Play
Billing quedan fuera del MVP. Ver [contrato del simulador](subscription-simulation.md). No mezclar pagos en etapa 13.

**Etapa 15:** cierre de recorridos, trazabilidad completa, OpenAPI versionado, README, documentación de API
y eventual publicación de Swagger bajo autorización separada. No habilitar Swagger público en 13A.

## Contratos estabilizados en 13A

- `GET /api/v1/animals/{animalId}` conserva sus campos y añade `speciesId: UUID` y `photoUrl: String|null`.
  Ambos ya estaban persistidos; photoUrl en blanco se normaliza a null. No cambia la respuesta del listado.
  La ficha ofrece los campos editables para reconstruir el PUT existente, incluido version.
- `PUT /api/v1/animals/{animalId}` sigue siendo una edición completa: enviar los campos no modificados,
  conservar UUID y usar la última version. La omisión de opcionales puede borrarlos; no se convirtió en PATCH.
  Una versión obsoleta conserva HTTP 409 ANIMAL_VERSION_CONFLICT, sin sobrescritura silenciosa.
- Los avisos `VISIT_SCHEDULED` describen visita/control, animal (nombre/código), veterinario, fecha/hora,
  finca y ubicación o «Ubicación no registrada». La hora informativa usa **America/Lima**, explícita en el texto;
  las APIs de agenda mantienen Instant ISO-8601 y no cambian campos ni zonas almacenadas.
- `NotificationView` conserva exactamente id/type/title/message/read/createdAt. Son mensajes informativos,
  no un mecanismo de deep-link. Se congela el significado, no el texto literal como API de parsing: Android
  no debe extraer identificadores de message. El contenido detallado queda en la bandeja autorizada;
  el canal consola/push recibe solo un aviso genérico para no copiar animal/finca/ubicación a logs.
- `POST /api/v1/attentions` rechaza con HTTP 400 VALIDATION_ERROR los elementos null de treatments o
  vaccinations, con details.field indicando el elemento. Listas ausentes, null o vacías siguen admitidas.
  La solicitud inválida no crea atención/complementos/indicaciones/avisos ni completa la cita.
- PUT/GET perfil, GET lista/detalle de fincas, REJECT de invitación y POST primera indicación conservan
  sus rutas, DTO y reglas. Se añaden pruebas HTTP de éxito, propiedad y permisos, sin reimplementarlos.

## Compatibilidad y límites

Las adiciones del DTO de animal son compatibles con clientes que toleran campos JSON nuevos. Los clientes
con deserialización estricta deben actualizar su modelo o permitir claves desconocidas. No se debe construir
una imagen de archivo local y llamarla URL remota; en este MVP solo se guarda una URL opcional.

No se modifican V1–V18, roles, autenticación, staging gate, HMAC, sesiones, proveedores o flags operativos.
No hay campos geográficos/profesionales nuevos, agenda ganadero, CRUD de fincas, reprogramación, FCM,
uploads ni tablas nuevas. Los tests usan PostgreSQL desechable y correo apagado; no autorizan operaciones
en Render, bases persistentes o integraciones reales. Merge requiere revisión y autorización del Team Leader.
