# ANITEC Backend

API REST para ganaderos y veterinarios. Java 21, Spring Boot 3.3.5, PostgreSQL, Flyway, JWT y OpenAPI.

## Arquitectura

Monolito modular con cinco bounded contexts:

| Contexto | Responsabilidad |
|---|---|
| Identity and Access (`identity`) | Registro, verificación, perfiles y sesiones |
| Livestock Management (`livestock`) | Fincas, animales, especies, observaciones y capacidad |
| Veterinary Linking (`linking`) | Invitaciones, vinculaciones y capacidad veterinaria |
| Veterinary Care (`care`) | Visitas, atenciones, tratamientos, vacunas e indicaciones |
| Subscriptions (`subscriptions`) | Planes, suscripciones y límites por perfil |

`notification` es un módulo de apoyo. Los únicos roles funcionales son `GANADERO` y `VETERINARIO`. No existe un usuario administrativo ni un panel para gestionar otras cuentas o catálogos.

## Ejecución local

Requisitos: JDK 21, Maven Wrapper incluido y PostgreSQL local. No cambiar la versión Java del proyecto para adaptarla al equipo.

1. Configurar la conexión local mediante las variables de la tabla siguiente si difiere de los valores de desarrollo.
2. Ejecutar `./mvnw spring-boot:run` en macOS/Linux o `.\mvnw.cmd spring-boot:run` en Windows. También puede ejecutarse `AnitecApplication` desde IntelliJ.
3. Consultar [Swagger](http://localhost:8080/swagger-ui/index.html), [OpenAPI](http://localhost:8080/v3/api-docs) y [health](http://localhost:8080/actuator/health).

La aplicación ejecuta Flyway hasta V11 y Hibernate valida el esquema. No usar una base compartida o remota para pruebas locales. V11 elimina datos del rol retirado: revisar la autorización de cualquier entorno antes de desplegar.

| Variable | Comportamiento |
|---|---|
| `SPRING_DATASOURCE_URL` | Por defecto: `jdbc:postgresql://localhost:5432/anitec` |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | Credenciales exclusivamente locales por defecto; configurar las del entorno sin incorporarlas a Git |
| `DATABASE_URL` | Alternativa PostgreSQL de Render; la URL JDBC explícita tiene prioridad |
| `PORT` | Por defecto: 8080 |
| `SPRING_PROFILES_ACTIVE` | `prod` para producción; `dev` para desarrollo explícito |
| `JWT_SECRET` | Obligatorio y seguro en `prod`; valor de desarrollo disponible solo fuera de producción |
| `DEMO_SEED` | `true` por defecto local; debe ser `false` en `prod` |
| `RESEND_API_KEY` / `RESEND_FROM` | Configuración de correo real; no guardar sus valores en el repositorio |
| `PAYMENTS_SIMULATION_ENABLED` | `false` por defecto; solo permite simulación con perfil `dev` o `test`, nunca `prod` |
| `CORS_ALLOWED_ORIGINS` | Orígenes separados por coma; revisar antes de exponer la API |

Para simular pagos desde IntelliJ se requieren ambos: perfil `dev` y `PAYMENTS_SIMULATION_ENABLED=true`. Sin proveedor habilitado, checkout y confirmación devuelven HTTP 503 con `errorCode=PAYMENTS_UNAVAILABLE`.

Sin Resend, no se envía correo y registro/invitaciones informan `emailDelivery=UNCONFIRMED`. No se registran destinatarios, asuntos, cuerpos ni códigos de verificación. Los usuarios demo locales sembrados por V9 ya están verificados. Las pruebas obtienen sus códigos desde el repositorio de su base desechable. El mensaje de registro distingue envío aceptado de envío no confirmado.

## Migraciones y retirada del tercer rol

- V1–V10 se conservan intactas como historial.
- V9 contiene un bootstrap histórico. Sus dos placeholders se resuelven con valores inertes internos, no con una cuenta real ni variables administrativas del operador.
- `V11__remove_admin_role.sql` elimina esa cuenta y sus referencias, retira la bitácora administrativa y restringe el esquema a los dos roles del producto, antes del arranque de la aplicación.
- Las instalaciones que ya tienen V1–V10 se actualizan con V11. Las instalaciones nuevas ejecutan V1–V11; no queda ninguna cuenta privilegiada al finalizar.
- No se requiere configurar un hash administrativo. Los nombres de placeholder históricos solo existen para interpretar V9 y no son un mecanismo de acceso vigente.
- No ejecutar `flyway repair`, no editar migraciones ya aplicadas y no cambiar la línea base automáticamente.
- V11 es destructiva respecto de los datos retirados. Probarla en PostgreSQL desechable no autoriza aplicarla a Render ni a recursos compartidos.

Los catálogos de especies y planes se conservan en V8. Sus cambios se gestionan mediante datos/migraciones versionadas y revisadas, sin CRUD privilegiado REST. Se conservan `GET /api/v1/species` y `GET /api/v1/subscription/plans`.

## Seguridad

- `prod` rechaza JWT ausente, corto o igual al valor de desarrollo antes de Flyway. Exige semillas demo deshabilitadas y prohíbe la simulación, incluso con perfiles combinados.
- El filtro JWT acepta únicamente roles funcionales y comprueba que la cuenta exista, esté activa y verificada y que el rol coincida. Los JWT del rol retirado y los roles desconocidos reciben HTTP 401.
- Las antiguas rutas `/api/v1/admin/**` no tienen controladores y se deniegan expresamente. No se convierten en endpoints públicos.
- Los refresh tokens asociados a cuentas retiradas se eliminan por su FK.
- No se implementan Stripe, i18n, plantillas, reintentos ni outbox en esta etapa. La renovación histórica y otras reglas pendientes requieren ramas posteriores.

## Pruebas

Con Docker Engine activo:

```powershell
.\mvnw.cmd -B test
```

Las integraciones utilizan PostgreSQL 16-alpine desechable mediante Testcontainers. No utilizan la base local persistente ni Render y deshabilitan Resend. Las SmokeTests habilitan pagos simulados explícitamente en `dev,test`.

La suite incluye los siete flujos originales, regresiones de JWT/correo/pagos, ausencia de rutas administrativas y migraciones desde cero y desde V10. Los resultados reales de cada ejecución se documentan en informes externos, no se deducen de la existencia del código.

El script opcional `scripts/seed-demo-data.sql` proporciona datos adicionales de desarrollo para los dos perfiles y no inserta bitácoras administrativas. Ejecutarlo solo sobre una base local explícitamente descartable y migrada hasta V11.

## Despliegue

No hay despliegue automático autorizado por ejecutar pruebas o modificar el repositorio. Antes de desplegar en Render, revisar conexión, perfil `prod`, JWT, correo, orígenes permitidos y el impacto destructivo de V11 con autorización específica. El blueprint existente debe revisarse por separado respecto de sus rutas de Docker; esta etapa no corrige ese problema previo.
