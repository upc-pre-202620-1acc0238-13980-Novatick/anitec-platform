# Guía REST compartida para Flutter/Dart y Android/Kotlin

API v1 del MVP académico. La misma especificación sirve a ambos clientes; no implementar reglas diferentes
según framework. [OpenAPI completa](openapi.yaml) y [decisiones funcionales](../mvp-stage-13-decisions.md).
Los ejemplos usan identificadores/correos ficticios y marcadores: sustituirlos por valores de la operación real.

## Entorno y URL base

| Entorno | Base y restricciones |
| --- | --- |
| Laboratorio Windows | `http://127.0.0.1:18080/api/v1`, con el helper del README; descartable |
| Android Emulator estándar | `http://10.0.2.2:18080/api/v1` referencia al host del emulador; solo configuración de depuración local autorizada |
| Otro equipo/dispositivo | URL del backend de desarrollo autorizado; configurar red/firewall de forma deliberada, no copiar direcciones de infraestructura privada |
| Staging privado | URL entregada fuera de la documentación pública; barrera adicional en todas las rutas API, incluidos registro/login |
| Producción | URL HTTPS que autorice el equipo; no se fija ni despliega desde esta PR |

No incluir STAGING_ACCESS_KEY en una APK distribuible ni en un bundle Flutter web. Esa clave es una
barrera para clientes API privados, no otra autenticación de usuario. Con clave válida se sigue exigiendo
JWT en rutas privadas. Flutter web tampoco debe intentar eludir preflight/CORS de staging.
Para desarrollar ambos clientes use un backend local autorizado; un acceso público distribuible requiere
una decisión independiente de entorno. HTTP sin TLS es solo laboratorio, no una recomendación productiva.

## Formatos comunes y validación

- `Content-Type: application/json` en cuerpos; UTF-8.
- UUID son strings opacos. No derivar propiedad ni permisos a partir de un UUID conocido.
- Instantes: ISO-8601 UTC, por ejemplo `2035-06-01T14:30:00Z`. El cliente presenta la zona al usuario.
- Fechas de nacimiento/atención/aplicación: `yyyy-MM-dd`, sin hora ni conversión de zona.
- Precios JSON numéricos con moneda separada. Mantener precisión decimal; el cliente no fija importes.
- `version` de animal es un entero no negativo, obligatorio para PUT y sujeto a conflictos.
- `expiresIn` del token se expresa en **segundos**, no milisegundos.
- El sobre ApiResponse omite sus propiedades nulas. Los DTO anidados sí pueden devolver null,
  por ejemplo subscription.endsAt, photoUrl o campos opcionales de perfil. Un campo ausente tampoco equivale a error de parseo.
- Campos opcionales de entrada pueden admitir null; un PUT de edición completa puede borrar valores
  opcionales omitidos/nulos. Conservar los que no se quieren cambiar, utilizando el DTO actual de consulta.
- Tolerar campos de respuesta nuevos y utilizar enums/documentación vigentes, sin asumir listas arbitrarias.

Sobre de éxito:

```json
{"success":true,"message":"Operación realizada con éxito","data":{"id":"11111111-1111-4111-8111-111111111111"},"timestamp":"2035-06-01T14:30:00Z"}
```

`data` depende de la operación y puede omitirse. Confirmar recuperación devuelve **204 sin cuerpo**:
no intentar decodificarlo como el sobre JSON. El texto de message no es un identificador estable de negocio.

Error:

```json
{"success":false,"message":"Datos de solicitud inválidos","errorCode":"VALIDATION_ERROR","details":[{"field":"name","error":"obligatorio"}],"timestamp":"2035-06-01T14:30:00Z"}
```

`details` es opcional y sus elementos utilizan **field/error**, no field/message. La política de contraseña
vigente exige al menos ocho caracteres, mayúscula, minúscula, número y símbolo; aplicar además validación
servidor. No registrar cuerpos con contraseñas/códigos, cabeceras Authorization ni refresh tokens.

## Registro, correo e idioma

POST `/auth/register` (sin JWT):

```json
{"name":"Persona","lastName":"Ejemplo","email":"persona@example.invalid","password":"<contraseña privada que cumpla la política>","role":"GANADERO"}
```

El otro rol permitido es VETERINARIO. Respuesta 201: data.accountId, email y emailDelivery.
Actualmente el registro devuelve UNCONFIRMED, también cuando se preparó un trabajo durable; no prueba
aceptación de Resend ni recepción en el buzón. No contiene código ni tokens. La cuenta queda pendiente.
Un 409 EMAIL_ALREADY_REGISTERED debe tratarse según el formulario, sin intentar crear otra identidad.

POST `/auth/verify`: email y **code como string de seis dígitos**, preservando ceros iniciales. Password
es opcional como contexto autorizado para detalles de verificación. Código: 10 minutos, cinco intentos
incorrectos por emisión. Sin contexto válido los fallos no deben utilizarse para inferir estado de cuentas.
Éxito 200 no crea sesión; continuar con login.

POST `/auth/resend-code`: email y password válidos para **emitir** otro código. Solo email, credenciales
incorrectas o falta de contexto devuelven 202 genérico, sin emisión y normalmente sin data. Una operación
autorizada puede incluir data.email, emailDelivery, remainingResends y nextResendAt. Cinco reenvíos por
ventana fija de 24 horas, primera emisión gratuita y espera mínima de 60 segundos. No crea sesión normal.
Los detalles de disponibilidad se exponen únicamente en contexto autorizado. Respetar Retry-After si llega 429.

Cabecera opcional `Accept-Language` en registro, reenvío, solicitud de recuperación e invitación:

```http
Accept-Language: en-US,en;q=0.9,es;q=0.5
```

Solo correo es/en. Variantes y preferencias ponderadas válidas se resuelven al idioma base; falta, invalidez
o ausencia de idioma compatible conserva español. No persiste preferencia: enviar de nuevo en cada
operación que deba usar inglés. El idioma se captura antes de la cola y queda materializado en el payload
cifrado del outbox. En invitaciones decide el emisor, sin asumir preferencia del veterinario. No traduce
JSON, rutas ni códigos de error. [Reglas detalladas](../linking-eligibility-email-i18n.md).

Sin correo externo autorizado no se puede completar la verificación real desde la app. No extraer códigos
de PostgreSQL ni agregar endpoints de depuración para resolverlo. Los tests utilizan emisores controlados;
eso no es un contrato público ni habilita proveedores en staging.

## Login, rotación y logout

POST `/auth/login` con email/password. Requiere cuenta activa y verificada. Respuesta 200 data:
accountId, email, role, accessToken, refreshToken y expiresIn. Guardar tokens en almacenamiento seguro del
cliente y excluirlos de logs/telemetría. Para cada petición privada:

```http
Authorization: Bearer <access token de esa sesión>
```

Los logins crean sesiones independientes. POST `/auth/refresh-token` no necesita JWT y recibe:

```json
{"refreshToken":"<refresh de la sesión>","operationId":"11111111-1111-4111-8111-111111111111"}
```

Generar un UUID **v4 nuevo por rotación lógica**. Ante timeout/reintento de la misma rotación, conservar
el mismo refresh y operationId; durante 60 segundos puede recuperarse la misma pareja sin extender
la vigencia. No generar otro operationId por cada reintento de red. Coordinar solicitudes concurrentes
del cliente para que una sesión no rote varias veces a la vez. Un replay fuera de tolerancia o con otra
operación puede revocar esa sesión. REFRESH_CONFIRMATION_PENDING (503) exige reintentar la misma operación.
La vida absoluta de sesión es de siete días; rotar no la reinicia.

POST `/auth/logout` recibe `{"refreshToken":"<refresh de esa sesión>"}`. Revoca inmediatamente la cadena
de esa sesión y sus access tokens, no las demás. No necesita JWT y es idempotente incluso si ya se cerró.
No usar sessionId ni operationId como credencial de logout. Descartar tokens locales después del cierre.

## Recuperación de contraseña

Deshabilitada por defecto: request/confirm devuelven **503 RECOVERY_UNAVAILABLE** sin saltar validaciones.
No mostrar un éxito ficticio ni intentar habilitarla desde el cliente.

Cuando el operador la habilite separadamente con canal y claves válidas:

1. POST `/auth/password-recovery/request` con email y, opcionalmente, Accept-Language.
2. 202 genérico indica aceptación para procesamiento, no existencia de cuenta ni entrega.
3. POST `/auth/password-recovery/confirm` con email, code string de seis dígitos y newPassword.
4. Éxito 204, sin tokens: invalida todas las sesiones de esa cuenta; requiere login nuevo.

RECOVERY_CODE_INVALID (400) no debe usarse para enumerar cuentas. Una confirmación incorrecta no revoca
las sesiones; una válida consume el código una sola vez. Verificación y recuperación tienen cuotas
independientes. No reutilizar la UI de reenvío como recuperación ni prometer disponibilidad si el canal está apagado.

## Datos ganaderos y atención clínica

Use las rutas/DTO exactos de [OpenAPI](openapi.yaml); no enviar contratos diseñados solo desde los mockups.

| Flujo | Contrato y decisión vigente |
| --- | --- |
| Perfil | GET/PUT /account/me; mismo perfil común, sin exigir colegiatura/especialidad/zonas nuevas |
| Fincas | POST/GET /farms, GET /farms/{farmId}, GET /farms/{farmId}/animals; location es texto opcional, no UBIGEO |
| Inventario | /animals con filtros search/species/status/farmId; totalActive y content; sin paginación general inventada |
| Editar animal | GET /animals/{animalId} aporta speciesId, photoUrl y version; PUT conserva UUID y requiere version vigente |
| Código | Automático si se omite al registrar, personalizado normalizado si se proporciona; único e históricamente reservado por ganadero entre fincas |
| Invitaciones | POST /invitations, GET /invitations/pending y PUT /invitations/{invitationId}/respond con ACCEPT/REJECT; solo destinatario elegible acepta |
| Vínculos | /vet-links y /vet-links/my-farmers según rol; DELETE /vet-links/{linkId} solo ganadero propietario |
| Agenda | GET /visits con from/to UTC y programación en /visits/schedule; pertenece al veterinario, no al ganadero |
| Próximas visitas del ganadero | GET /visits/upcoming; solo GANADERO, todas las citas futuras PROGRAMADA de sus animales y fincas, sin límite de 30 días |
| Atención | POST /attentions con fecha y descripción; visitId opcional. Máximo una atención por cita/control, varias independientes por animal |
| Seguimiento | /visits/follow-up referencia attentionId; su control puede tener su propia atención |
| Historial | GET /animals/{animalId}/medical-history; revocar quita acceso al veterinario y conserva lectura/historial del ganadero |
| Bandeja | GET /notifications con page/size, PATCH /notifications/{notificationId}/read; solo del destinatario; no FCM ni deep-link garantizado |

Un veterinario vinculado accede únicamente a información autorizada. Otro ganadero no puede leer/editar
un animal conociendo su UUID; ser Premium no amplía ese alcance. Un 409 ANIMAL_VERSION_CONFLICT exige
recargar, presentar el conflicto y volver a editar conscientemente; no sobrescribir automáticamente.
ATTENTION_REQUIRED (409) impide completar una cita sin atención; ATTENTION_ALREADY_EXISTS (409) impide
otra atención de la misma cita. Atención clínica y complementos respetan autoría y vínculo activo.

Para una finca sin location, mostrar **Ubicación no registrada**. La visita identifica al animal y permite
consultar la finca autorizada; no hay una dirección geográfica nueva ni una ubicación persistida por cita.

### Próximas visitas del GANADERO

`GET /api/v1/visits/upcoming` exige `Authorization: Bearer <accessToken>` y cuenta GANADERO
activa/verificada con sesión vigente. En staging privado sigue siendo obligatoria la barrera de acceso
del entorno. VETERINARIO recibe **403 FORBIDDEN**; sin JWT válido se conserva el **401** oficial.
`GET /api/v1/visits` sigue siendo exclusivamente la agenda del veterinario y no cambia su contrato.

Respuesta **200**, sobre `ApiResponse<List<FarmerAppointmentView>>`; `data` es `[]` si no hay citas:

```json
{
  "success": true,
  "message": "Operación realizada con éxito",
  "data": [{
    "id": "11111111-1111-4111-8111-111111111111",
    "animalId": "22222222-2222-4222-8222-222222222222",
    "animalName": "Nina",
    "animalCode": "AN-000001",
    "farmerName": "Ganadero Prueba",
    "vetName": "Veterinario Prueba",
    "type": "VISITA",
    "scheduledAt": "2035-06-01T14:30:00Z",
    "status": "PROGRAMADA",
    "reason": "Evaluación"
  }],
  "timestamp": "2035-06-01T12:00:00Z"
}
```

`type` es `VISITA` o `CONTROL`; `reason` puede ser null. `animalName` utiliza el código si el
animal no tiene nombre. Los nombres corresponden a los perfiles actuales. El listado incluye únicamente
`scheduledAt > ahora del servidor` y estado `PROGRAMADA`, ordenado por fecha ascendente y UUID para
desempatar. Kotlin debe convertir el instante UTC a la zona del usuario para presentarlo.

No hay paginación, truncamiento ni filtros en este contrato: una llamada devuelve todas las citas
que cumplen esas condiciones, de todas las fincas y veterinarios correspondientes. No enviar ownerId,
farmerId, animalId, from/to, page/size ni status: parámetros desconocidos no amplían el alcance.
La identidad se obtiene del JWT; se verifica también la propiedad actual del animal y de su finca.
La lectura del propietario no requiere una vinculación veterinaria activa; revocar el vínculo no
cancela automáticamente una cita ni concede al veterinario acceso adicional.

## Planes y pago simulado

**Simulación académica. No se realizará ningún cobro.** No solicitar ni almacenar tarjetas.

GET `/subscription/plans` determina precio y capacidad para el perfil; GET `/subscription/my-subscription`
es la fuente del estado actual. Límites: ganadero 15/150, veterinario 3/25. Referencia Premium PEN 19.90/39.90.

POST `/subscription/premium` con planId prepara una operación PENDING de la propia cuenta, sin activar.
checkoutId es una referencia opaca **no una URL de Stripe**. Confirmar en
POST `/subscription/premium/confirm` con checkoutId y APPROVED/DECLINED. Conservar la referencia al
reintentar; un resultado definitivo no cambia por enviar otro outcome y no acredita dos períodos.

Para una nueva renovación durante Premium vigente:

```json
{"planId":"<plan Premium vigente del servidor>","renewal":true}
```

Sin renewal/false/null se mantiene contratación y un Premium ya vigente recibe 409. Cada extensión
requiere una operación nueva aprobada; el scheduler no la inventa. Un mes calendario UTC se añade desde
el vencimiento vigente; si ya venció, una nueva aprobación acredita desde la confirmación.

POST `/subscription/cancel-renewal` mantiene beneficios acreditados hasta vencer, deshabilita renovación
y resuelve pendientes como DECLINED. Tras vencer se puede contratar de nuevo. El backend aplica capacidades
gratuitas sin eliminar exceso, vínculos o historial; nuevas altas excedidas fallan. Refrescar suscripción y
capacidad después de aprobar, rechazar, cancelar o detectar vencimiento.

El simulador solo funciona en dev/test con habilitación explícita. prod/staging devuelven
PAYMENTS_UNAVAILABLE (503), incluso con JWT o clave de staging válidos. No desbloquear la UI suponiendo que
puede concederse Premium allí. [Contrato completo de simulación](../subscription-simulation.md).

## Errores y reintentos

| HTTP | Acción del cliente |
| --- | --- |
| 400 | VALIDATION_ERROR/details o RECOVERY_CODE_INVALID; corregir entrada, no repetir a ciegas |
| 401 | INVALID_CREDENTIALS, SESSION_INVALID, ACCESS_TOKEN_EXPIRED; distinguir credenciales de login de vencimiento de una sesión |
| 403 | Falta de permiso/vínculo o STAGING_ACCESS_DENIED; refrescar no concede acceso |
| 404 | Recurso inexistente/referenciado; no fabricar otro UUID |
| 409 | Conflicto de versión, estado, duplicado o atención; resolver según errorCode |
| 422 | Regla de negocio: cupo, plan, elegibilidad o código de verificación |
| 429 | Respetar Retry-After; no inferir cuentas desde respuestas públicas |
| 503 | Función deshabilitada, autenticación/canal indisponible o confirmación pendiente; consultar errorCode antes de reintentar |
| 500 | Fallo inesperado sanitizado; no revelar cuerpos/credenciales en telemetría |

Notificaciones sí tienen page/size y total; otros listados conservan su contrato específico. No imponer
ordenamientos/paginación que OpenAPI y los controladores no ofrecen. Mantener requests distinguibles
por su intención y no repetir mutaciones sin conocer su idempotencia.

## Límites de la candidata

Premium se reconcilia por consulta o barrido de 30 minutos. Sin ambos, los límites previos pueden
permanecer temporalmente; una interrupción del servicio puede prolongarlo. La app refresca antes de
presentar capacidades, pero la autorización sigue siendo del backend.

No se acredita aquí recepción real de correo, entrega FCM, UI móvil, carga productiva ni una pasarela.
Las decisiones aplazadas siguen en [alcance MVP](../mvp-stage-13-decisions.md) y la evidencia en
[matriz de aceptación](../mvp-functional-acceptance.md). La candidata no es cierre definitivo hasta merge y CI
satisfactorio sobre el SHA final de develop.
