# ANITEC Backend

API REST de gestión ganadera y atención veterinaria (modular monolith con **DDD**), construida con
**Java 21 + Spring Boot 3.3 + PostgreSQL**, lista para desplegarse en **Render.com**.

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

### Despliegues siguientes

- Cada `git push` a la rama principal dispara un nuevo deploy automático.
- Si añades migraciones, créalas como `V11__descripcion.sql`, `V12__…` (Flyway las aplica solas en
  el siguiente arranque). **Nunca** edites una migración ya aplicada: su *checksum* deja de cuadrar
  y la app no arranca.

### Opción B — Recursos manuales (sin blueprint)

Si prefieres crearlos a mano:

1. **New + → PostgreSQL** → nombre `anitec-db`, database `anitec`.
2. **New + → Web Service** → selecciona el repositorio y:
   - **Runtime:** Docker
   - **Dockerfile Path:** `anitec-backend/Dockerfile`
   - **Docker Build Context:** `anitec-backend/` (raíz del repo si subiste solo el backend)
   - **Health Check Path:** `/actuator/health`
3. Copia las variables del `render.yaml`: `SPRING_PROFILES_ACTIVE=prod`, `PORT=8080`,
   `DATABASE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `JWT_SECRET`,
   `ADMIN_SEED_PASSWORD_HASH`, `ADMIN_SEED_EMAIL`, `DEMO_SEED=false`.

> `SPRING_PROFILES_ACTIVE=prod` es obligatorio en esta modalidad: sin él, `DEMO_SEED` vuelve a su
> defecto `true` y se insertarían los usuarios de demostración en producción.

### Notas y solución de problemas

| Síntoma | Causa / solución |
| --- | --- |
| `Blueprint Path` no encontrado | Debe ser `anitec-backend/render.yaml` (ver Paso 2) |
| El servicio no pasa el health check | Mira **Logs**: casi siempre es una migración fallida o un `JWT_SECRET` menor a 32 caracteres |
| `password_hash` vacío en producción | Ver [ADMIN_SEED_PASSWORD_HASH](#admin_seed_password_hash-obligatorio) |
| Primera petición muy lenta | En el plan gratuito el servicio se suspende por inactividad y el *cold start* tarda hasta ~1 minuto |
| PostgreSQL `plan: free` no disponible | Render puede no ofrecer PostgreSQL gratuito en tu cuenta: cambia `plan: free` por `plan: starter` en `databases` de `render.yaml` (o elige el plan correspondiente al crear la BD a mano) |
| Correos de verificación no llegan | Sin `RESEND_API_KEY` solo van a los logs: **anitec-backend → Logs** |
| `DATABASE_URL` no se usa | Solo aplica si **no** defines `SPRING_DATASOURCE_URL` explícitamente (el blueprint no la define) |
