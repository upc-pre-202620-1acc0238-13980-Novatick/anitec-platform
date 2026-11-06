# ANITEC — Simulated Payment Lifecycle & Subscription Stabilization

Etapa 14, alcance aprobado el 2026-11-06. El simulador académico existente sustituye la integración
Stripe prevista anteriormente. **No se realizan transacciones financieras ni se solicitan tarjetas.**
Stripe Test Mode, Checkout, webhooks y Google Play Billing quedan fuera del MVP. Un proveedor financiero
futuro necesitaría una integración y validación independientes; no debe confiar en el resultado del cliente.

## Entornos y autorización

| Configuración | Iniciar/confirmar/renovar simulación |
| --- | --- |
| Perfil dev o test y payments.simulation-enabled=true | Permitido con JWT de cuenta activa/verificada |
| dev/test sin habilitación explícita | HTTP 503 PAYMENTS_UNAVAILABLE |
| Sin dev/test, o perfil desconocido | HTTP 503 PAYMENTS_UNAVAILABLE |
| prod o staging, incluso combinados con dev/test | Nunca se selecciona FakePaymentGateway |

En prod, una configuración que habilita simulación impide el arranque mediante la validación productiva
existente. En staging se siguen exigiendo prod y la barrera de acceso. La exclusión del gateway funciona
también con combinaciones inconsistentes de perfiles. La clave de staging no habilita pagos simulados.

Las consultas siguen disponibles para una cuenta autenticada; cancel-renewal únicamente desactiva
renovaciones y nunca concede beneficios nuevos. No se creó una ruta pública ni un rol privilegiado.
Para la demo Android utilizar un backend local aislado con perfil dev y habilitación explícita. No habilitar
el simulador en Render para demostrar este flujo ni incorporar claves de staging a una APK distribuible.

## Planes definidos por el servidor

| Perfil | Gratuito | Premium | Referencia mensual |
| --- | ---: | ---: | ---: |
| GANADERO | 15 animales activos | 150 animales activos | PEN 19.90 |
| VETERINARIO | 3 ganaderos vinculados | 25 ganaderos vinculados | PEN 39.90 |

El backend obtiene precio, moneda, perfil y capacidad del catálogo persistido. Android selecciona un planId
devuelto por plans; no decide importes, límites ni propietario. Premium no concede acceso a datos ajenos.

## Guía y contratos para Android

Base `/api/v1/subscription`. Todas las rutas requieren `Authorization: Bearer <access token>` y utilizan
el sobre existente `{success,message,data,errorCode?,details?,timestamp}`. No registrar tokens en la app.
Los cinco endpoints conservan método, ruta y campos de respuesta. Éxitos HTTP 200.

| Método y ruta | Entrada | data de salida |
| --- | --- | --- |
| GET /plans | Sin cuerpo | Lista PlanView compatible con el rol |
| GET /my-subscription | Sin cuerpo | SubscriptionView, reconciliando vencimiento si corresponde |
| POST /premium | PremiumRequest | CheckoutResult de operación ficticia PENDING |
| POST /premium/confirm | ConfirmRequest | PaymentResultView con resultado definitivo |
| POST /cancel-renewal | Sin cuerpo | SubscriptionView, con renewalEnabled=false |

### Preparar contratación

```json
{"planId":"<UUID del plan Premium compatible>"}
```

No activa Premium. Solicitudes repetidas mientras hay un pago pendiente devuelven el mismo checkoutId.
Se serializa por cuenta para evitar dos operaciones pendientes nuevas incluso con solicitudes concurrentes.
No se añade Idempotency-Key público: la confirmación es idempotente por checkoutId. Tras resolver una operación,
otra contratación/renovación explícita representa una operación nueva.

### Preparar renovación

Ampliación mínima de contrato: campo opcional **renewal: true** en el mismo POST /premium:

```json
{"planId":"<UUID del plan Premium vigente>","renewal":true}
```

Ausente, null o false conserva el comportamiento de contratación anterior, incluido HTTP 409 si Premium
ya está vigente. La renovación explícita evita confundir un reintento de compra con la decisión de adquirir
otro período. Exige Premium no vencido, mismo plan y renewalEnabled=true. No cambia DTO de salida ni tablas.
Después de vencer, se inicia una nueva contratación con renewal ausente/false.

### Confirmar resultado ficticio

Android muestra **«Simulación académica. No se realizará ningún cobro»** antes de ofrecer aprobación/rechazo.
No solicita tarjetas ni presenta el flujo como Stripe.

```json
{"checkoutId":"<referencia opaca del backend>","outcome":"APPROVED"}
```

También admite DECLINED. La cuenta del JWT debe ser propietaria de la operación y la suscripción.
Un resultado ya procesado es inmutable: repetir APPROVED/DECLINED devuelve el estado final del pago y el
estado actual de la suscripción, sin aplicar efectos de nuevo. Una respuesta APPROVED repetida después
del vencimiento no reactiva Premium. El frontend debe refrescar my-subscription y capacidad, no deducir
beneficios actuales únicamente del estado histórico del pago.

La primera aprobación activa un mes calendario UTC desde la confirmación. Una nueva aprobación durante
un período vigente extiende un mes calendario UTC desde endsAt, sin perder días restantes. Se incrementa
revision y se propaga la capacidad con los eventos transaccionales existentes. Un rechazo no cambia
plan, vigencia, revision ni capacidad. No hay cobro ni renovación automática.

Una operación pendiente iniciada antes del vencimiento puede confirmarse posteriormente: si entonces
ya venció, su aprobación acredita un nuevo período desde la confirmación, sin retroactividad. La operación
no tiene un TTL nuevo en esta etapa. No existe una segunda aprobación de esa misma referencia.

### Cancelar y vencer

cancel-renewal es idempotente mientras el plan sigue siendo Premium. Conserva endsAt y beneficios vigentes,
deshabilita renovación y resuelve como DECLINED los pagos pendientes de la suscripción. Una aprobación
posterior de esas referencias no puede extender el período. No se ofrece reactivar renovación durante el
período cancelado; después de vencer puede iniciarse una nueva contratación.

Si confirmación y cancelación compiten, el bloqueo determina el orden: si se aprobó primero, conserva el
período ya acreditado; si canceló primero, no se acredita otro. En ambos casos queda renewalEnabled=false.

Al vencer, el plan pasa a GRATUITO, status VENCIDA y endsAt=null; revision aumenta. Se aplican 15/3 mediante
los consumidores de capacidad existentes sin eliminar animales, vínculos ni registros clínicos. Si ya se
excede el límite gratuito, se conservan los datos y se rechazan nuevas altas por INVENTORY_LIMIT_REACHED
o LINKING_LIMIT_REACHED hasta liberar cupo o acreditar Premium.

El scheduler conserva su frecuencia de 30 minutos y **no extiende períodos**. GET my-subscription también
reconcilia un vencimiento en una transacción, actualiza capacidades y evita mostrar Premium vencido.
Sin consulta ni barrido, las proyecciones de capacidad de otros contextos pueden mantener el último límite
hasta el próximo barrido; no se promete invalidación al nanosegundo del vencimiento. Android debe refrescar
la suscripción antes de mostrar capacidades/contratar; la propagación sigue siendo responsabilidad backend.
Una interrupción del scheduler requiere supervisión operativa. No se añadió coordinación nueva entre contextos.

### Campos y errores

- PlanView: id, name, profile, type, price, currency, billingPeriod, capacityLimit, active.
- SubscriptionView: subscriptionId, planId, planName, planType, status, allowedCapacity, renewalEnabled,
  endsAt (puede omitirse por la política NON_NULL), revision, price, currency, billingPeriod.
- CheckoutResult: checkoutId, status, planName, amount, currency. checkoutId es una referencia opaca,
  no una URL externa. El estado inicial es PENDING.
- PaymentResultView: checkoutId, paymentStatus, subscriptionId, planType, allowedCapacity, revision, endsAt.
- UUID para identificadores; fechas Instant ISO-8601 UTC; precio BigDecimal con moneda PEN, sin cálculos
  financieros desde Android. Un mes calendario no equivale necesariamente a 30 días.

| HTTP / código | Situación |
| --- | --- |
| 400 VALIDATION_ERROR | planId/outcome/checkoutId obligatorios, formato inválido, plan gratuito no contratable |
| 401 | JWT ausente, inválido, cuenta o sesión no utilizable |
| 403 FORBIDDEN | Intento de confirmar una operación ajena; staging rechaza antes del JWT si falta su clave |
| 404 NOT_FOUND | Plan, suscripción o referencia de pago inexistente |
| 409 INVALID_STATE_TRANSITION | Compra con Premium vigente, renovación sin Premium/tras cancelación o plan incompatible con el vigente |
| 422 PLAN_PROFILE_MISMATCH | Plan de otro perfil |
| 422 PLAN_UNAVAILABLE | Plan inactivo |
| 422 INVALID_STATE_TRANSITION | Cancelación cuando no tiene plan Premium |
| 503 PAYMENTS_UNAVAILABLE | Simulador no autorizado; ninguna aprobación del cliente evita el control |

Los errores conservan el sobre y validaciones globales existentes; Android utiliza errorCode y details,
sin depender del texto de message. Los campos no reconocidos no sustituyen valores del catálogo.

## Persistencia, concurrencia y límites

Se reutilizan las tablas y restricciones de V5. V1–V18 intactas, sin V19. La fila de suscripción se bloquea
con PESSIMISTIC_WRITE antes de leer pagos o modificar el ciclo. Checkout, confirmación, cancelación, consulta
reconciliadora y vencimiento usan el mismo orden. Los candidatos de vencimiento son UUID escalares y se
revalidan bajo bloqueo, evitando leer un agregado viejo antes de esperar.

Pago resuelto, suscripción, revisiones, capacidades y eventos se confirman juntos o revierten juntos.
Una aprobación también resuelve como DECLINED otros pendientes heredados, sin acreditarlos; el servicio
no genera duplicados nuevos. La unicidad de pendientes se garantiza por bloqueo/aplicación, no por una
restricción SQL nueva: escrituras administrativas SQL externas no están autorizadas por este contrato.

Las pruebas utilizan PostgreSQL 16 temporal, HTTP local, perfiles explícitos y datos sintéticos. Incluyen
repetición concurrente, resultados contrapuestos, cancelación concurrente, rollback y recreación completa
del contexto. No sustituyen pruebas de carga, validación de una pasarela real ni despliegue.
