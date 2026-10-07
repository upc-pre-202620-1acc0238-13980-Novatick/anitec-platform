# ANITEC Backend

API REST de gestión ganadera y atención veterinaria (modular monolith con **DDD**), construida con
**Java 21 + Spring Boot 3.3 + PostgreSQL**, lista para desplegarse en **Render.com**.

Especificación completa: [`../anitec-backend-spec.md`](../anitec-backend-spec.md).

---

## Contenido

1. [Contextos acotados](#contextos-acotados-bounded-contexts)
2. [Requisitos](#requisitos)
3. [Ejecutar en local](#ejecutar-en-local)
4. [Pruebas](#pruebas)
5. [Desplegar en Render.com](#desplegar-en-rendercom)

## Contextos acotados (bounded contexts)

| Contexto | Responsabilidad |
| --- | --- |
| `identity` | Cuentas, verificación de correo (código de 6 dígitos / 10 min), sesiones JWT + refresh tokens |
| `livestock` | Fincas, especies, animales, observaciones y capacidad de inventario |
| `linking` | Invitaciones y vinculaciones ganadero↔veterinario + capacidad de vinculaciones |
| `care` | Visitas/controles, atenciones clínicas, tratamientos, vacunaciones e indicaciones versionadas |
| `subscriptions` | Planes, suscripciones, pagos (gateway falso) y límites por perfil |
| `notification` | Notificaciones in-app + puerto push (FCM) |
| `admin` | Usuarios, catálogos, planes y bitácora de auditoría |

## Requisitos

| Para | Necesitas |
| --- | --- |
| Ejecutar la API | **JDK 21** (`java -version`) y **Docker** en marcha |
| Compilar | Nada más: se usa el wrapper incluido (`mvnw`), no hace falta instalar Maven |
| Pruebas | Docker (Testcontainers levanta `postgres:16-alpine`) |
| Desplegar | Cuenta en [Render.com](https://render.com) + repositorio en GitHub |

> **Windows:** si `java` o `mvnw` no se reconocen, instala un JDK 21 y define la variable de
> entorno `JAVA_HOME` apuntando a la carpeta del JDK antes de ejecutar los comandos.

### Estructura del repositorio (importante para Render)

El blueprint `render.yaml` asume que la **raíz del repositorio Git** contiene la especificación y
la carpeta del backend:

```
novatick/                        ← raíz del repositorio (lo que se sube a GitHub)
├── anitec-backend-spec.md
├── requirements.md
└── anitec-backend/              ← este proyecto
    ├── Dockerfile               ← imagen multi-etapa (Maven + JRE 21)
    ├── render.yaml              ← Blueprint Path en Render: anitec-backend/render.yaml
    ├── pom.xml
    ├── mvnw / mvnw.cmd
    └── src/
```

---

## Ejecutar en local

### Paso 1 — Levantar PostgreSQL

```bash
docker run --name anitec-pg \
  -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=anitec \
  -p 5432:5432 -d postgres:16

# esperar a que esté lista («ready to accept connections» y Ctrl+C)
docker logs -f anitec-pg
```

> **PowerShell:** ejecuta el `docker run` en **una sola línea** (PowerShell no continúa con `\`):
>
> ```powershell
> docker run --name anitec-pg -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=anitec -p 5432:5432 -d postgres:16
> ```

Con estos valores por defecto no hay que configurar nada más:
`jdbc:postgresql://localhost:5432/anitec` / usuario `postgres` / contraseña `postgres`.

### Paso 2 — Arrancar la API

```bash
./mvnw spring-boot:run        # macOS / Linux
```

```powershell
.\mvnw.cmd spring-boot:run    # Windows (PowerShell / CMD)
```

En el primer arranque:

1. se descargan las dependencias (puede tardar unos minutos);
2. **Flyway** aplica las migraciones `V1` … `V10` (esquema por contexto + planes, catálogos y
   cuentas sembradas); Hibernate corre en `ddl-auto: validate`;
3. con `DEMO_SEED=true` (valor por defecto en local) se insertan los datos de demostración.

Deja la terminal abierta: los correos y los códigos de verificación se imprimen en consola mientras
no exista `RESEND_API_KEY`.

### Paso 3 — Verificar

| Recurso | URL |
| --- | --- |
| Swagger UI | <http://localhost:8080/swagger-ui.html> |
| OpenAPI (JSON) | <http://localhost:8080/v3/api-docs> |
| Health | <http://localhost:8080/actuator/health> → `{"status":"UP"}` |

### Paso 4 — Primer login (smoke test)

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"demo.ganadero@anitec.pe","password":"Anitec!Demo123"}'
```

En **PowerShell** usa `curl.exe` (porque `curl` es un alias de `Invoke-WebRequest`):

```powershell
curl.exe -s -X POST http://localhost:8080/api/v1/auth/login `
  -H "Content-Type: application/json" `
  -d '{"email":"demo.ganadero@anitec.pe","password":"Anitec!Demo123"}'
```

La respuesta trae `data.accessToken`; úsalo en el resto de endpoints protegidos:

```bash
curl -s http://localhost:8080/api/v1/animals \
  -H "Authorization: Bearer <accessToken>"
```

### Paso 5 (opcional) — Variables de entorno

Todas son opcionales en local (tienen valores por defecto):

| Variable | Por defecto en local | Descripción |
| --- | --- | --- |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/anitec` | URL JDBC si no usas el contenedor por defecto |
| `SPRING_DATASOURCE_USERNAME` | `postgres` | Usuario de la BD |
| `SPRING_DATASOURCE_PASSWORD` | `postgres` | Contraseña de la BD |
| `PORT` | `8080` | Puerto HTTP |
| `JWT_SECRET` | clave solo-desarrollo | Clave de los JWT; **≥ 32 caracteres** (si no, la app no arranca) |
| `RESEND_API_KEY` | vacío | Sin clave Resend, los correos van a la consola |
| `RESEND_FROM` | `ANITEC <onboarding@resend.dev>` | Remitente de los correos |
| `CORS_ALLOWED_ORIGINS` | `*` | Orígenes permitidos, separados por coma |
| `ADMIN_SEED_EMAIL` | `admin@anitec.pe` | Correo de la cuenta admin (migración V9) |
| `ADMIN_SEED_PASSWORD_HASH` | vacío | Vacío → hash local de `Anitec!2026Admin` (**obligatorio en Render**) |
| `DEMO_SEED` | `true` | `false` en Render: no inserta usuarios ni datos demo |

```powershell
# ejemplo en PowerShell (una sesión)
$env:RESEND_API_KEY = "re_..."
$env:CORS_ALLOWED_ORIGINS = "http://localhost:5173"
.\mvnw.cmd spring-boot:run
```

```bash
# ejemplo en bash
RESEND_API_KEY=re_... CORS_ALLOWED_ORIGINS=http://localhost:5173 ./mvnw spring-boot:run
```

### Cuentas sembradas (V9, solo local/demo)

| Perfil | Correo | Contraseña |
| --- | --- | --- |
| Admin | `admin@anitec.pe` | `Anitec!2026Admin` |
| Ganadero demo | `demo.ganadero@anitec.pe` | `Anitec!Demo123` |
| Veterinario demo | `demo.veterinario@anitec.pe` | `Anitec!Demo123` |

> V9 solo siembra estas 3 cuentas. Las otras 4 de demostración (`demo.ganadero2`,
> `demo.veterinario2`, `demo.ganadero3`, `demo.suspendido`) y todo el dataset ampliado vienen de
> [`scripts/seed-demo-data.sql`](scripts/seed-demo-data.sql) (ver
> [Datos de prueba (demo) en Render](#datos-de-prueba-demo-en-render)).

### Paso 6 — Reiniciar desde cero

El contenedor no usa volumen, así que basta con recrearlo (Flyway volverá a crear el esquema):

```bash
docker rm -f anitec-pg
# vuelve a ejecutar el Paso 1 y después el Paso 2
```

---

## Pruebas

```bash
./mvnw verify        # macOS / Linux
.\mvnw.cmd verify    # Windows
```

- 7 pruebas E2E (`SmokeTests`) que levantan la API en un puerto aleatorio contra un
  **PostgreSQL real gestionado por Testcontainers** (`postgres:16-alpine`): Docker debe estar
  corriendo y la primera ejecución descarga la imagen.
- `verify` también empaqueta el `.jar` en `target/`.

---

## Desplegar en Render.com

El repositorio ya incluye todo:

- **`render.yaml`** → blueprint: servicio web + PostgreSQL gestionado con sus variables de entorno.
- **`Dockerfile`** → build multi-etapa (Maven `package -DskipTests` + JRE 21 Alpine).

### Paso 1 — Subir el código a GitHub

Ejecuta estos comandos desde la **carpeta raíz** (`novatick/`, la que contiene `anitec-backend/`),
o sube esa misma carpeta desde GitHub Desktop:

```bash
git init
git add .
git commit -m "ANITEC backend"
git remote add origin https://github.com/<tu-usuario>/<tu-repo>.git
git push -u origin main
```

La **raíz del repositorio** debe ser la carpeta que contiene `anitec-backend/` y la
especificación (ver [Estructura del repositorio](#estructura-del-repositorio-importante-para-render)),
porque `render.yaml` referencia `./anitec-backend/Dockerfile`.

### Paso 2 — Crear el Blueprint en Render

1. Entra a [dashboard.render.com](https://dashboard.render.com) → **New +** → **Blueprint**.
2. Conecta tu cuenta de GitHub y selecciona el repositorio.
3. En **Blueprint Path** escribe exactamente:

   ```
   anitec-backend/render.yaml
   ```

   > El archivo está dentro de `anitec-backend/`, no en la raíz: si dejas el valor por defecto
   > (`render.yaml`) Render no lo encontrará.
4. Revisa los recursos que propone y pulsa **Apply**.

Render crea:

| Recurso | Tipo | Detalle |
| --- | --- | --- |
| `anitec-db` | PostgreSQL | Base `anitec`; su *connection string* se inyecta como `DATABASE_URL`, más usuario y contraseña |
| `anitec-backend` | Web Service | Runtime **Docker** con `anitec-backend/Dockerfile`, health check `/actuator/health`, `SPRING_PROFILES_ACTIVE=prod`, `PORT=8080` |

### Paso 3 — Variables de entorno **antes** del primer deploy

En **anitec-backend → Environment**:

#### `ADMIN_SEED_PASSWORD_HASH` (obligatorio)

Genera el hash bcrypt de la contraseña que quieras para el admin:

```powershell
# Windows (requiere Docker)
((docker run --rm httpd:2.4 htpasswd -nbB -C 10 admin "TuClaveSegura") -split ':', 2)[1]
```

```bash
# macOS / Linux / Git Bash
docker run --rm httpd:2.4 htpasswd -nbB -C 10 admin "TuClaveSegura" | cut -d: -f2
```

Sale algo como `$2y$10$UWyhiBqexVepznzySBOSEOmG5zJvEOUOTjETJ.VSMA7YcSvlz3feK`.
Copia **solo el hash** (sin `usuario:` ni los dos puntos) en la variable y guarda.

> ⚠️ **Debe fijarse antes del primer deploy.** La migración `V9__seed_accounts.sql` corre una sola
> vez: si la variable queda vacía, la cuenta admin nace con la contraseña local de respaldo
> `Anitec!2026Admin`.
>
> Si ya desplegaste con el hash vacío, corrígelo manualmente desde
> **anitec-db → Data → Connect** (consola `psql`):
>
> ```sql
> UPDATE identity_accounts
>    SET password_hash = '$2y$10$...'   -- el hash generado arriba
>  WHERE role = 'ADMIN';
> ```

#### `JWT_SECRET` (recomendado)

El blueprint lo marca como `generateValue: true` (Render genera uno al crear el servicio), pero es
mejor fijar el tuyo para que no cambie si recreas el entorno:

```powershell
python -c "import secrets; print(secrets.token_urlsafe(48))"
```

Sin Python basta cualquier cadena aleatoria larga (p. ej. una contraseña generada de 48+
caracteres). Debe tener **32 caracteres o más**; si cambias este valor, todos los tokens emitidos
anteriormente dejan de ser válidos.

#### Opcionales

| Variable | Para qué |
| --- | --- |
| `RESEND_API_KEY` / `RESEND_FROM` | Envío real de correos de verificación. Sin clave, los códigos aparecen en los logs de Render |
| `CORS_ALLOWED_ORIGINS` | Origen de tu app móvil/web (p. ej. `https://mi-app.netlify.app`); por defecto `*` |
| `ADMIN_SEED_EMAIL` | Correo del admin (por defecto `admin@anitec.pe`) |
| `DEMO_SEED` | Déjalo en `false` para **no** insertar usuarios/datos demo en producción |

### Paso 4 — Primer deploy

1. En **anitec-backend → Manual Deploy → Clear build cache & deploy** (o simplemente espera a que
   el blueprint despliegue).
2. Render construye la imagen con el `Dockerfile` y arranca el contenedor.
3. Al arrancar, la app convierte `DATABASE_URL` (`postgres://…`) a URL JDBC automáticamente y
   Flyway aplica `V1` … `V10`.
4. Comprueba el health check:

   ```bash
   curl https://anitec-backend.onrender.com/actuator/health   # {"status":"UP"}
   ```

   Swagger queda público (decisión #20 de la spec):
   <https://anitec-backend.onrender.com/swagger-ui.html>

### Paso 5 — Smoke test en producción

```bash
curl -s -X POST https://anitec-backend.onrender.com/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@anitec.pe","password":"<la contraseña cuyo hash configuraste>"}'
```

Usa `data.accessToken` con `Authorization: Bearer <accessToken>` para probar el resto de
endpoints (la lista completa está en Swagger).

### Datos de prueba (demo) en Render

Los usuarios `demo.ganadero@anitec.pe` y `demo.veterinario@anitec.pe` **no se crean en
producción a propósito**: `render.yaml` fija `DEMO_SEED=false` y la migración
`V9__seed_accounts.sql` solo siembra ese bloque cuando el placeholder `demo_seed` es `true`.

En Render solo existen, por tanto, la cuenta admin (`ADMIN_SEED_EMAIL`) y las cuentas que vayas
creando por API. Si el login del admin funciona, la API y la base están bien: solo faltan esos
datos. Tres formas de obtenerlos:

#### Opción A (recomendada) — sembrarlos a mano

No toca Flyway ni el flag `DEMO_SEED`.

1. Render → **`anitec-db` → Data/Connect → Open in Console**. Si tu plan no incluye la consola
   web, conéctate desde tu máquina con `psql` usando la **External Database URL** de la pestaña
   *Connections*.
2. Copia y ejecuta [`scripts/seed-demo-data.sql`](scripts/seed-demo-data.sql). Crea **6 cuentas de
   demostración** (todas con contraseña `Anitec!Demo123`) y un conjunto de datos completo para
   recorrer la app:

   | Qué siembra | Detalle |
   |---|---|
   | Cuentas | `demo.ganadero` y `demo.veterinario` (gratuitas), `demo.ganadero2` y `demo.veterinario2` (**PREMIUM**, con pagos `APPROVED`), `demo.ganadero3` y `demo.suspendido` (esta última **SUSPENDIDA**, para ver el bloqueo de login y el panel de admin) |
   | Producción | 4 fincas y **15 animales** de las 5 especies (1 `VENDIDO`), con 8 observaciones |
   | Vinculación | 8 invitaciones (2 `PENDIENTE`, 4 `ACEPTADA`, 1 `RECHAZADA`, 1 `VENCIDA`) y 4 vínculos (1 `REVOCADA`); hay una invitación **pendiente en la bandeja de cada veterinario** |
   | Atenciones | 8 citas (4 futuras `PROGRAMADA`, 1 futura `CANCELADA`, 2 pasadas `COMPLETADA`, 1 pasada `CANCELADA`) y 6 historias clínicas con tratamientos, vacunas e instrucciones **versionadas (2 versiones)** |
   | Otros | 11 notificaciones, 7 entradas de auditoría de admin, 3 pagos (2 `APPROVED` + 1 `DECLINED`) |

   Los contadores de capacidad (`active_animals` / `active_links`) se **recalculan al final** del
   script para que siempre cuadren con las filas reales.

3. Al terminar, el script imprime **5 consultas de verificación**: deben salir 6 cuentas (5
   `ACTIVA` + 1 `SUSPENDIDA`), las capacidades cuadrando con los contadores reales (p. ej.
   `8/15` para `demo.ganadero`, `2/3` para `demo.veterinario`), invitaciones con los 4 estados y
   citas con `PROGRAMADA` siempre futura y `COMPLETADA`/`CANCELADA` ya vencidas.
4. Vuelve a probar el login (p. ej. `demo.ganadero2@anitec.pe` → plan premium).

El script es **idempotente** (todos los `INSERT` llevan `ON CONFLICT … DO NOTHING`): se puede
repetir sin duplicar filas ni descuadrar los checksums de Flyway, y **no** incluye al admin (ese
lo siembra siempre V9).

#### Opción B — que lo siembre Flyway

Más enrevesada: hay que reabrir la migración V9, que está **por debajo** de V10 ya aplicada, así
que además necesitas permitir migraciones fuera de orden.

1. **Environment** → agrega `DEMO_SEED=true` y `SPRING_FLYWAY_OUT_OF_ORDER=true` → **Save**.
2. En la consola SQL: `DELETE FROM flyway_schema_history WHERE version = '9';`
3. **Deployments → Manual Deploy → Clear build cache & deploy** (Flyway re-ejecuta V9 con
   `demo_seed=true`).
4. Quita `SPRING_FLYWAY_OUT_OF_ORDER` (y vuelve a `DEMO_SEED=false` si ya no lo quieres).

> Los `INSERT` de V9 llevan `ON CONFLICT … DO NOTHING`, así que el admin existente no se pisa y
> tu contraseña se conserva. Si V9 falla porque no corrió la migración 9, vuelve a desplegar.

#### Opción C — registrarlos por API (solo cuentas, sin datos de ejemplo)

`POST /api/v1/auth/register` desde Swagger de producción: el evento `AccountRegistered` crea la
suscripción gratuita y la capacidad. El código de verificación de 6 dígitos sale en **Logs** (no
hay `RESEND_API_KEY`). **No** crea finca, animales ni vínculo, así que el flujo de demostración
quedaría vacío.

> Si algún día recreas la base de datos, estos datos desaparecen: repite la Opción A.

### Despliegues siguientes

- Cada `git push` a la rama principal dispara un nuevo deploy automático.
- Si añades migraciones, créalas como `V11__descripcion.sql`, `V12__…` (Flyway las aplica solas en
  el siguiente arranque). **Nunca** edites una migración ya aplicada: su *checksum* deja de cuadrar
  y la app no arranca.

### Opción B — Recursos manuales (sin blueprint)

Úsala si prefieres crear cada recurso tú mismo, si tu cuenta no permite blueprints o si quieres
entender qué está haciendo Render por detrás. Es el **mismo resultado** que la Opción A, pero las
piezas las conectas tú.

> **Antes de empezar:** sube el código a GitHub (Paso 1 de arriba). El resto de pasos de la
> Opción A no hacen falta aquí.

**El orden importa:** primero la base de datos, luego el servicio web. Si creas el servicio antes,
su primer deploy fallará porque todavía no existe `DATABASE_URL`.

| Paso | Qué creas | Resultado |
| --- | --- | --- |
| [B.1](#b1--crear-la-base-de-datos) | PostgreSQL `anitec-db` | Base de datos + credenciales |
| [B.2](#b2--generar-los-secretos-antes-de-continuar) | Secretos | `JWT_SECRET` y `ADMIN_SEED_PASSWORD_HASH` listos para pegar |
| [B.3](#b3--crear-el-servicio-web) | Web Service `anitec-backend` | Contenedor construido con el `Dockerfile` del repo |
| [B.4](#b4--variables-de-entorno) | Variables de entorno | Conexión a la BD, perfil `prod` y secretos |
| [B.5](#b5--primer-deploy) | Deploy | Flyway + arranque + health check en verde |
| [B.6](#b6--smoke-test) | Verificación | Login de admin correcto en producción |

> La UI de Render cambia con frecuencia: el texto exacto de los botones puede variar un poco, pero
> los campos y los valores son los mismos.

#### B.1 — Crear la base de datos

1. En el dashboard de Render: **New +** → **PostgreSQL**.
2. Rellena los detalles de la instancia:

   | Campo | Valor |
   | --- | --- |
   | **Name** | `anitec-db` |
   | **Database Name** | `anitec` |
   | **Region** | la más cercana a tus usuarios (para Latinoamérica suele ser **Oregon** o **Ohio**) |
   | **PostgreSQL Version** | **16** o superior (con eso se prueban las migraciones) |
   | **Instance Type / Plan** | el que tu cuenta tenga disponible (gratis si Render te lo ofrece) |

3. Pulsa **Create Database** y **espera a que el estado pase a `Available`**.
4. Entra a la pestaña **Connections** de la base recién creada y copia a un bloc de notas:

   | Dato de Connections | Para qué lo vas a usar |
   | --- | --- |
   | **Internal Database URL** (`postgres://…`) | `DATABASE_URL` |
   | **User** | `SPRING_DATASOURCE_USERNAME` |
   | **Password** (botón *Show*) | `SPRING_DATASOURCE_PASSWORD` |
   | **Database** | `anitec` |

> ⚠️ La **Internal Database URL** solo funciona desde otros servicios de Render (misma región:
> más rápida y sin coste de tráfico). Para conectarte desde tu máquina usa la **External Database
> URL**.

#### B.2 — Generar los secretos (antes de continuar)

Reutiliza los comandos de la sección [Paso 3 — Variables de entorno](#paso-3--variables-de-entorno-antes-del-primer-deploy):

- [`ADMIN_SEED_PASSWORD_HASH`](#admin_seed_password_hash-obligatorio) → **obligatorio** (genera
  el hash con `htpasswd` en Docker y copia **solo** el `$2y$10$…`)
- [`JWT_SECRET`](#jwt_secret-recomendado) → cualquier cadena de **32+ caracteres**

Déjalos pegados en el bloc de notas junto a los de B.1: los usarás en B.4. No crees el servicio
sin tenerlos a mano: **el hash solo se puede usar si se define antes del primer arranque**
(migración `V9`).

#### B.3 — Crear el servicio web

1. **New +** → **Web Service**.
2. Conecta tu cuenta de GitHub (Render usa la app de GitHub), elige el repositorio y la rama
   `main`, y pulsa **Connect**.
3. Configura el servicio con estos valores:

   | Campo | Valor |
   | --- | --- |
   | **Name** | `anitec-backend` |
   | **Region** | **exactamente la misma que la base de datos** |
   | **Branch** | `main` |
   | **Runtime** | **Docker** (no «Native environment») |
   | **Dockerfile Path** | `anitec-backend/Dockerfile` → o `./Dockerfile` si tu repositorio es solo el backend |
   | **Docker Build Context** | `anitec-backend` → o `.` si tu repositorio es solo el backend |
   | **Root Directory** | déjalo vacío |
   | **Health Check Path** | `/actuator/health` |
   | **Instance Type / Plan** | el que tu cuenta tenga disponible |

4. Si el formulario te deja agregar las variables de entorno **antes** de crear el servicio,
   déjalas para el paso B.4 (así las pegas todas de una vez). Crea el servicio.
5. **Primer build:** Render clona el repo, ejecuta `mvn package -DskipTests` dentro del contenedor
   y prepara la imagen. Tarda varios minutos. Es normal que este primer deploy **falle** (todavía
   no hay `DATABASE_URL`); no corrijas nada todavía: sigue a B.4.

#### B.4 — Variables de entorno

Entra a **anitec-backend → Environment** y agrega las variables una a una (o pega todas de golpe
con el **Raw Editor**, formato `CLAVE=VALOR`):

| Variable | Valor | De dónde sale |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | `prod` | fijo |
| `DATABASE_URL` | Internal Database URL de `anitec-db` | B.1 |
| `SPRING_DATASOURCE_USERNAME` | usuario de `anitec-db` | B.1 |
| `SPRING_DATASOURCE_PASSWORD` | contraseña de `anitec-db` | B.1 |
| `JWT_SECRET` | cadena de 32+ caracteres | B.2 |
| `ADMIN_SEED_PASSWORD_HASH` | hash bcrypt `$2y$10$…` | B.2 |
| `ADMIN_SEED_EMAIL` | `admin@anitec.pe` | fijo |
| `DEMO_SEED` | `false` | fijo |
| `PORT` | `8080` | opcional: Render asigna uno si no lo defines y la app lo respeta igual |
| `RESEND_API_KEY` | clave de Resend | opcional (sin ella, los correos van a los logs) |
| `RESEND_FROM` | `ANITEC <onboarding@resend.dev>` | opcional |
| `CORS_ALLOWED_ORIGINS` | dominio de tu app móvil/web | opcional (por defecto `*`) |

**Guarda** los cambios antes de continuar. Dos avisos importantes:

- `SPRING_PROFILES_ACTIVE=prod` es **obligatorio** en esta modalidad: sin él, `DEMO_SEED` vuelve a
  su valor por defecto `true` y se insertarían los usuarios/datos de demostración en producción.
- **No** agregues `SPRING_DATASOURCE_URL`: esa variable tiene prioridad sobre `DATABASE_URL` y
  anula la conversión automática `postgres://…` → `jdbc:postgresql://…`, con lo que tendrías que
  escribir la URL JDBC a mano.

#### B.5 — Primer deploy

1. **Deployments → Manual Deploy → Clear build cache & deploy**.
2. Sigue la pestaña **Logs** y comprueba este orden exacto:

   | Orden | Qué aparece | Significado |
   | --- | --- | --- |
   | 1 | `Successfully built …` (o `Pulling/Building …`) | La imagen se construyó con tu `Dockerfile` |
   | 2 | `Flyway … Migrating schema "public" to version "1 - init_identity"` … `version "10 - invitation_expiry_and_capacity_locking"` | Las 10 migraciones se aplicaron sobre la BD nueva |
   | 3 | `Started AnitecApplication` | La API arrancó |
   | 4 | Estado del servicio → **Live** (verde) | El health check `/actuator/health` responde 200 |

   Si se queda en `Building` mucho tiempo, revisa los logs del paso 1 (casi siempre es la ruta del
   `Dockerfile` mal escrita en B.3). Si se para en el paso 3, revisa B.4 (falta `DATABASE_URL` o
   credenciales).

3. Comprobación rápida desde tu máquina:

   ```bash
   curl https://anitec-backend.onrender.com/actuator/health   # {"status":"UP"}
   ```

   (usa el nombre real de tu servicio si lo diste distinto.)

#### B.6 — Smoke test

```bash
curl -s -X POST https://anitec-backend.onrender.com/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@anitec.pe","password":"<la contraseña cuyo hash configuraste en B.2>"}'
```

Si devuelve `data.accessToken`, todo está conectado. Prueba el resto de endpoints desde Swagger:
<https://anitec-backend.onrender.com/swagger-ui.html>.

#### Diferencias con la Opción A (blueprint)

| | Opción A (blueprint) | Opción B (manual) |
| --- | --- | --- |
| Creación de recursos | Render los crea desde `render.yaml` | Los creas tú, campo a campo |
| Variables de entorno | Se sincronizan desde el blueprint | Las agregas y mantienes a mano |
| Cambios futuros (nuevas variables) | Editas `render.yaml` → **Apply** | Las agregas manualmente en **Environment** |
| Riesgo de olvidar algo | Bajo | Medio: si olvidas `prod`, `DATABASE_URL` o el hash, el deploy falla o siembra datos demo |
| Configuración como código | Sí (el `render.yaml` queda versionado) | No |

Al final, **ambas opciones construyen exactamente la misma imagen** con el mismo `Dockerfile`.

### Notas y solución de problemas

| Síntoma | Causa / solución |
| --- | --- |
| `Blueprint Path` no encontrado | Debe ser `anitec-backend/render.yaml` (ver Paso 2) |
| El servicio no pasa el health check | Mira **Logs**: casi siempre es una migración fallida o un `JWT_SECRET` menor a 32 caracteres |
| `password_hash` vacío en producción | Ver [ADMIN_SEED_PASSWORD_HASH](#admin_seed_password_hash-obligatorio) |
| Login de `demo.ganadero@anitec.pe` / `demo.veterinario@anitec.pe` falla | Esperado en Render: `DEMO_SEED=false` no siembra los usuarios demo → ver [Datos de prueba (demo) en Render](#datos-de-prueba-demo-en-render) |
| Primera petición muy lenta | En el plan gratuito el servicio se suspende por inactividad y el *cold start* tarda hasta ~1 minuto |
| PostgreSQL `plan: free` no disponible | Render puede no ofrecer PostgreSQL gratuito en tu cuenta: cambia `plan: free` por `plan: starter` en `databases` de `render.yaml` (o elige el plan correspondiente al crear la BD a mano) |
| Correos de verificación no llegan | Sin `RESEND_API_KEY` solo van a los logs: **anitec-backend → Logs** |
| `DATABASE_URL` no se usa | Solo aplica si **no** defines `SPRING_DATASOURCE_URL` explícitamente (el blueprint no la define) |
| (Opción B) Falla con `Connection refused localhost:5432` | Faltan `DATABASE_URL` o las credenciales en **Environment** (paso B.4) o `SPRING_PROFILES_ACTIVE` no es `prod` |
| (Opción B) El build falla enseguida con «no se encuentra el Dockerfile» | Revisa **Dockerfile Path** y **Docker Build Context** (B.3): son `anitec-backend/…` si el repo contiene también las especificaciones, o `./…` si el repo es solo el backend |
