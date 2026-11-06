# Acta candidata de cierre — ANITEC Backend MVP

**Estado: candidata; no declaración definitiva de cierre.**

Base funcional integrada: PR #16, merge SHA `92bc81c09c66823589b8f85aff52f2e516f09cbc`, 541 pruebas.
Esta acta acompaña la PR de etapa 15B desde `feature/mvp-api-documentation` hacia develop.

## Identidad de la candidata

El SHA candidato es el **headRefOid de la PR 15B** y se registra completo, con resultados locales/CI y
hash del contrato, en el informe/acta externa final y la descripción de la PR. Se resuelve localmente con:

```sh
git rev-parse HEAD
```

No se incrusta el hash del propio commit en este archivo: modificarlo para escribir ese valor produciría
otro SHA. La copia externa posterior al commit contiene el valor resuelto, sin alterar la candidata.

- Formato: OpenAPI **3.0.1**.
- Versión informativa: **1.0.0-mvp-candidate**, rutas /api/v1.
- [Contrato versionado](api/openapi.yaml) y [copia pública](api/site/openapi-public.json).
- [Matriz de recorridos y pruebas](mvp-functional-acceptance.md).
- [Guía común Flutter/Kotlin](api/mobile-integration.md).

## Alcance acreditado

Registro/verificación/login de los dos roles, sesiones independientes, refresh y logout; recuperación
controlada y su modo deshabilitado; fincas, inventario, atributos y observaciones; aislamiento por cuenta;
invitación/elegibilidad/aceptación/rechazo/revocación; visitas, atenciones, tratamientos, vacunas,
indicaciones y controles con atención propia; historial conservado; bandeja in-app privada; ciclo Premium
simulado con límites por rol, idempotencia, renovación explícita, cancelación y ambos caminos de vencimiento.

La matriz cita pruebas concretas, no solo presencia de código. El informe final de esta PR registra
la suite completa ejecutada y Backend CI real, con cero fallos, errores y omisiones. Una especificación
generada no prueba por sí sola todos los comportamientos; se conserva la aceptación funcional anterior.

## Integraciones y exclusiones

**Simulación académica. No se realizará ningún cobro.** Premium usa pago ficticio solo dev/test autorizado.
No están integrados Stripe, Google Play Billing u otra pasarela; el simulador permanece bloqueado en
prod/staging. Bandeja interna y adaptador de consola no acreditan FCM real.

Resend/outbox están implementados pero deshabilitados por defecto, al igual que Password Recovery.
Las pruebas utilizan dobles/HTTP simulado sin credenciales reales; no prueban recepción en buzón.
No se desarrollaron clientes Flutter/Kotlin ni se modificó Figma. El visor público es estático de consulta,
no un backend nuevo ni un despliegue. No hay nuevas migraciones; V1–V18 permanecen intactas.

## Riesgos aceptados y límites

- La reconciliación Premium ocurre al consultar suscripción o mediante el barrido de 30 minutos.
  Otros contextos pueden conservar temporalmente la capacidad previa; la ventana supone scheduler operativo.
- No se promete serialización de revocaciones de vínculo con todas las operaciones clínicas ya iniciadas.
- La cola de intake previa a una emisión de recuperación puede perder una solicitud en un reinicio;
  una emisión confirmada y su outbox sí usan persistencia transaccional. Las invitaciones mantienen envío directo.
- Reintentos del proveedor no garantizan entrega exactamente una vez al buzón; no se implementaron webhooks.
- No se acreditan carga productiva, disponibilidad de Render, interfaces móviles ni proveedores externos reales.
- Las claves persistentes requieren custodia/rotación operativa; no deben regenerarse como en un laboratorio descartable.
- Swagger vendorizado requiere mantenimiento revisado; ninguna prueba certifica ausencia universal de vulnerabilidades.

## Integración móvil y entorno

Ambos clientes usan API v1, JWT, UUID/fechas y errores comunes. Deben respetar el operationId de rotación,
version de animal, Accept-Language y checkoutId/renewal. Refrescar capacidades no sustituye autorización backend.
No distribuir STAGING_ACCESS_KEY. El desarrollo local usa un entorno autorizado; la distribución pública
requiere una decisión de acceso independiente. [Instrucciones completas](api/mobile-integration.md).

## Condición de cierre definitivo

1. Revisar y aprobar esta PR de documentación/contrato.
2. Integrarla manualmente en develop; no hay autorización de merge automático.
3. Identificar el SHA resultante de develop y comprobar **Backend CI verde sobre ese SHA**, no solo sobre la rama.
4. Registrar entonces la declaración definitiva del Team Leader con el SHA y la evidencia correspondiente.

No se modifica main, Render o bases persistentes. No se autoriza despliegue ni publicación del sitio
por preparar estos documentos. Hasta cumplir esas condiciones, esta acta sigue siendo candidata.
