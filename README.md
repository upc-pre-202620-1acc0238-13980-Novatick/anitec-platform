# ANITEC Platform

Backend REST de **ANITEC**, desarrollado por NOVATICK — UPC para ganaderos y veterinarios.
Centraliza inventario, observaciones, vínculos profesionales y atención clínica para que la información
del ganado y sus cuidados no quede dispersa entre registros independientes.

El MVP académico ofrece una API común para **Flutter/Dart** y **Android/Kotlin**. Este repositorio contiene
el servidor; no incluye los clientes móviles. Los únicos roles funcionales son `GANADERO` y `VETERINARIO`.
No existe un usuario administrador interactivo.

## Funcionalidades

| Área | Alcance implementado |
| --- | --- |
| Identidad | Registro, verificación, perfil común, JWT, sesiones revocables, refresh rotatorio y recuperación con habilitación independiente |
| Ganadería | Crear/consultar fincas, animales con códigos automáticos o personalizados, edición versionada, estados, observaciones e historial |
| Vinculación | Invitaciones a veterinarios activos/verificados, aceptación, rechazo y revocación con aislamiento por cuenta |
| Atención | Agenda del veterinario, visitas, atenciones, tratamientos, vacunas, indicaciones y controles con atención propia |
| Notificaciones | Bandeja interna persistida y lectura por el destinatario; no equivale a entrega push |
| Suscripciones | Capacidades por perfil y ciclo de pago simulado: aprobación, rechazo, renovación explícita, cancelación y vencimiento |

El correo de seguridad dispone de HMAC, outbox cifrado y reintentos, con plantillas es/en seleccionadas
mediante `Accept-Language`. El proveedor externo y Password Recovery están **deshabilitados por defecto**.
Tener el código implementado no acredita configuración del proveedor ni recepción en un buzón.

## Tecnologías y arquitectura

| Elemento | Versión / uso |
| --- | --- |
| Java | 21 |
| Spring Boot | 3.3.5: Web MVC, Security, Validation, Data JPA y Actuator |
| PostgreSQL | 16 como referencia validada |
| Flyway | V1–V18; Hibernate valida el esquema |
| Maven | Wrapper 3.9.9 incluido |
| API | JJWT 0.12.6; Springdoc OpenAPI 2.6.0 |
| Calidad | JUnit 5, Spring Boot Test, Testcontainers 1.21.4 y GitHub Actions |

Monolito modular con organización DDD (`domain`, `application`, `infrastructure`, `interfaceapi`):

| Bounded context | Paquete | Responsabilidad |
| --- | --- | --- |
| Identity and Access | `identity` | Cuentas, perfiles, verificación, sesiones y recuperación |
| Livestock Management | `livestock` | Fincas, animales, especies y capacidad de inventario |
| Veterinary Linking | `linking` | Invitaciones, permisos de vinculación y capacidad veterinaria |
| Veterinary Care | `care` | Agenda, registros clínicos e indicaciones |
| Subscriptions | `subscriptions` | Planes, vigencia, pagos simulados y límites |

`notification` es un componente de apoyo, no un sexto bounded context; `shared` contiene infraestructura
y convenciones comunes. Los eventos internos propagan capacidades en la transacción existente. Los clientes
móviles consumen la misma API v1; no acceden a PostgreSQL ni sustituyen la autorización del servidor.

## Empezar en Windows: laboratorio aislado

Requisitos: **JDK 21**, **Docker Engine activo**, Git y **PowerShell 7**. No necesita instalar Maven.
No cambie la versión Java del proyecto para acomodarla al JDK instalado.

Desde la raíz del repositorio:

```powershell
.\mvnw.cmd -v
docker info
pwsh -NoProfile -File .\scripts\local-docs-lab.ps1 -ValidateOnly
```

El helper crea PostgreSQL 16 temporal en `127.0.0.1:15432`, inicia el backend en
`http://127.0.0.1:18080`, aplica V1–V18, comprueba health/OpenAPI y controles 401/503, y elimina solo sus
recursos al terminar. Genera credenciales sintéticas en memoria, sin escribir claves en Git. No utiliza
ni modifica `anitec-pg`, Render o bases persistentes.

Para mantenerlo activo hasta Ctrl+C:

```powershell
pwsh -NoProfile -File .\scripts\local-docs-lab.ps1
# Solo para demostrar Premium local, con habilitación explícita:
pwsh -NoProfile -File .\scripts\local-docs-lab.ps1 -EnablePaymentSimulation
```

Use `-DatabasePort` y `-ApplicationPort` si los puertos están ocupados. Ejecute el helper en un proceso
separado, como se muestra, **sin dot-sourcing**. Detenerlo elimina sus datos; no sirve para conservar
trabajo. No envía correos, habilita recuperación ni crea usuarios demo. Con correo deshabilitado el registro
queda pendiente: completar verificación requiere un canal autorizado configurado posteriormente.

## Configuración y arranque sobre otra base local

Prepare una base PostgreSQL 16 exclusiva de desarrollo y configure variables **en privado**. No incluya
valores secretos en comandos compartidos, capturas, archivos versionados o informes.

| Variable | Uso |
| --- | --- |
| `SPRING_DATASOURCE_URL` | URL JDBC explícita; tiene prioridad sobre `DATABASE_URL` |
| `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | Credenciales del entorno; nunca usar producción para pruebas |
| `SPRING_PROFILES_ACTIVE` | `dev` para desarrollo; staging requiere `prod,staging` |
| `EMAIL_VERIFICATION_HMAC_KEY` | Obligatoria en todo runtime: 32 bytes aleatorios Base64, independiente de otras claves |
| `SESSION_RECOVERY_KEY` | Obligatoria fuera de tests aislados: 32 bytes Base64; conservar entre reinicios |
| `JWT_SECRET` | Independiente; obligatorio y validado estrictamente en prod; configurar también en desarrollo compartido |
| `DEMO_SEED` | Recomendado `false`; obligatorio false en prod; no se requiere cuenta administrativa |
| `PORT` | Puerto HTTP; predeterminado 8080 |
| `EMAIL_DELIVERY_MODE` | `disabled` por defecto; `resend` requiere configuración y autorización operativa independientes |
| `RESEND_API_KEY`, `RESEND_FROM`, `EMAIL_OUTBOX_ENCRYPTION_KEY` | Solo para correo real autorizado; cifrado con clave independiente de 32 bytes Base64 |
| `PASSWORD_RECOVERY_HMAC_KEY` | Solo al habilitar recuperación, independiente de las demás. `identity.password-recovery.enabled` permanece false por defecto |
| `PAYMENTS_SIMULATION_ENABLED` | Opt-in solo dev/test; prod/staging lo prohíben |
| `STAGING_ACCESS_KEY` | Solo staging: 32 bytes Base64 independientes; no distribuir a clientes públicos |
| `CORS_ALLOWED_ORIGINS` | Orígenes web separados por comas; no sustituye JWT ni permisos |

En una base conservada, mantenga las claves de forma segura entre arranques. Regenerarlas puede invalidar
códigos o impedir recuperar payloads/resultados cifrados; consulte las guías antes de rotarlas.
`DATABASE_URL` es un fallback de conexión, no una invitación a conectarse a Render. No se proporcionan
credenciales ni hosts de despliegue en esta documentación.

Con la base y variables locales preparadas:

```powershell
.\mvnw.cmd spring-boot:run
```

En Linux/macOS, con las mismas variables, Java y Docker configurados:

```sh
./mvnw -v
./mvnw spring-boot:run
./mvnw -B test
```

El helper de laboratorio es específico de Windows. El arranque ejecuta Flyway y valida el esquema:
no experimente sobre bases compartidas ni edite migraciones aplicadas. V11 retira datos administrativos
históricos; V12/V13 incluyen preflight de integridad. Este README no autoriza `flyway repair`, reconstruir
bases persistentes ni desplegar.

## Entornos

| Función | dev | test aislado | prod | prod,staging |
| --- | --- | --- | --- | --- |
| API de negocio | JWT y permisos | Controles con fixtures | JWT y permisos | Barrera adicional más JWT y permisos |
| Pagos simulados | Solo opt-in | Solo opt-in | Bloqueados | Bloqueados incluso con clave/JWT válidos |
| Correo externo | Deshabilitado salvo configuración autorizada | Dobles/servidor simulado | Solo con configuración válida y habilitación independiente | Misma exigencia; la barrera no lo habilita |
| Password Recovery | False por defecto | Solo fixtures que lo necesitan | False por defecto | False por defecto |
| Swagger/OpenAPI vivos | Disponibles localmente | Probados localmente | El perfil prod solo no los apaga; no equivalen al sitio estático | Deshabilitados obligatoriamente |
| Health | Diagnóstico | Probado | Según configuración | Solo GET /actuator/health público, sin detalles |

No use staging sin prod: exige sus validaciones estrictas. No incluya STAGING_ACCESS_KEY en una APK,
bundle Flutter web o repositorio cliente. Para desarrollo móvil use un backend local autorizado;
una distribución pública requiere una decisión de entorno independiente, no eludir esa barrera.

## API y documentación

- Base REST: `/api/v1`; JSON con sobre `{success,message,data?,errorCode?,details?,timestamp}`.
- JWT en `Authorization: Bearer …` para rutas privadas, más propiedad, vínculo o autoría cuando corresponde.
- Registro, verificación y sesiones tienen condiciones propias; refresh requiere `operationId` UUID v4.
- Identificadores UUID; `Instant` UTC; fechas de calendario `yyyy-MM-dd`.
- [OpenAPI técnica versionada](docs/api/openapi.yaml), contrastada con la aplicación en tests.
- [Swagger estático de consulta](docs/api/site/index.html): preparado para publicación independiente,
  **no desplegado**, sin Try it out ni peticiones operativas al backend.
- [Guía común Flutter/Kotlin](docs/api/mobile-integration.md): autenticación, contratos, errores y ejemplos.
- [Mantenimiento y consulta local](docs/api/README.md): generación, validación y visor estático.

El Swagger vivo local usa `/swagger-ui/index.html`; no lo confunda con el sitio estático revisado.
Abrir el HTML desde GitHub no inicia el visor: sírvalo como indica su guía.

## Suscripciones académicas

**Simulación académica. No se realizará ningún cobro.**

| Perfil | Gratuito | Premium | Referencia mensual |
| --- | ---: | ---: | ---: |
| Ganadero | 15 animales activos | 150 animales activos | S/ 19.90 |
| Veterinario | 3 ganaderos vinculados | 25 ganaderos vinculados | S/ 39.90 |

Preparar una operación no activa Premium. Cada extensión exige una nueva operación aprobada; renovación
explícita con `renewal: true`. Un rechazo conserva vigencia y un replay no acredita otro período. Cancelar
mantiene beneficios acreditados y deshabilita renovaciones. Al vencer se aplican límites gratuitos sin
borrar animales, vínculos o historial, aunque excedan el cupo.

El servidor reconcilia al consultar la suscripción o mediante su barrido de 30 minutos. Sin ambos, otros
contextos pueden conservar temporalmente la capacidad previa; una interrupción puede prolongarlo.
Consulte [reglas y contratos de la simulación](docs/subscription-simulation.md).
Stripe, Google Play Billing y otras pasarelas financieras **no están integrados**.

## Pruebas y calidad

```powershell
docker info
.\mvnw.cmd -B test
```

Las integraciones usan PostgreSQL 16 desechable mediante Testcontainers; no necesitan iniciar `anitec-pg`.
Incluyen HTTP, concurrencia, sesiones, privacidad, migraciones, outbox y aceptación entre contextos.
Backend CI utiliza Java 21/Docker y rechaza fallos, errores u omisiones; no configure secretos reales allí.
El contrato versionado se compara con la API generada: un cambio deliberado exige regeneración y revisión,
no desactivar el test.

La base integrada de etapa 15A tiene **541 pruebas**. Los resultados candidatos figuran en la PR y
[acta candidata](docs/mvp-closure-candidate.md). Un resultado satisfactorio no demuestra recepción real de
correo/push, rendimiento productivo ni una interfaz móvil funcionando.

## Límites y guías complementarias

Fuera del MVP: FCM real, pagos financieros, edición de fincas, reprogramación de visitas, UBIGEO/coordenadas,
perfil profesional obligatorio, almacenamiento propio de fotos e internacionalización general del JSON.
`photoUrl` es una URL opcional; el correo admite es/en y las respuestas conservan sus mensajes actuales.

| Documento | Contenido |
| --- | --- |
| [Decisiones del MVP](docs/mvp-stage-13-decisions.md) | Alcance ratificado y exclusiones |
| [Aceptación integral](docs/mvp-functional-acceptance.md) | Matriz de recorridos y pruebas |
| [Correo seguro](docs/email-delivery.md) | Outbox, cifrado, reintentos y configuración |
| [Verificación y legado](docs/identity-legacy-hardening.md) | HMAC y protección de códigos |
| [Elegibilidad e idioma](docs/linking-eligibility-email-i18n.md) | Invitaciones y Accept-Language |
| [Barrera de staging](docs/staging-access-gate.md) | Clave independiente y health |
| [Suscripciones simuladas](docs/subscription-simulation.md) | Ciclo académico y límites |

La declaración definitiva de cierre espera merge de la PR de documentación y CI verde sobre el SHA final
de develop. Este repositorio y sus pruebas no autorizan modificar main ni desplegar en Render.
