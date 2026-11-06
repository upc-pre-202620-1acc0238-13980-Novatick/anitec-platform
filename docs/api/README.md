# Contrato API y Swagger de consulta

- [openapi.yaml](openapi.yaml): especificación técnica OpenAPI 3.0.1, info.version 1.0.0-mvp-candidate.
- [site/openapi-public.json](site/openapi-public.json): copia revisada para consulta, con servidor reservado inerte.
- [site/index.html](site/index.html): Swagger UI estático independiente del backend, sin ejecución.
- [mobile-integration.md](mobile-integration.md): guía común Flutter/Dart y Android/Kotlin.

## Generación y detección de drift

Desde la raíz, con Java 21 y Docker disponibles:

```powershell
.\mvnw.cmd -B '-Dtest=OpenApiContractTests' test
```

El test levanta el backend real contra PostgreSQL 16 Testcontainers, obtiene /v3/api-docs y compara el
documento normalizado con el YAML versionado y su copia pública. También contrasta las rutas/métodos
con RequestMappingHandlerMapping, los DTO de entrada con sus records, referencias, autorización y
estados/esquemas representativos mediante HTTP. No modifica bases persistentes ni envía correos.

Cuando un cambio deliberado de contrato fue autorizado:

```powershell
.\mvnw.cmd -B '-Dtest=OpenApiContractTests' '-Danitec.openapi.update=true' test
git diff -- docs/api/openapi.yaml docs/api/site/openapi-public.json
.\mvnw.cmd -B test
```

El modo de actualización se prohíbe en CI. Revisar y versionar el diff; no editar la copia pública
independientemente ni desactivar la comparación. La normalización ignora orden de claves y listas que
representan conjuntos; conserva valores, propiedades, validaciones y estructura. El contrato no inventa
operaciones para alcanzar una cifra: el inventario de esta candidata se obtiene de los controladores.

Los únicos cambios Java de 15B son metadatos de documentación: éxito 201/202/204, modelos de mapas,
seguridad por operación, errores y nombres separados de DTO para evitar colisiones. No cambian DTO JSON,
roles, filtros o reglas de negocio. La anotación de un código no sustituye una prueba de su comportamiento.

## Consultar el sitio sin backend

```powershell
python -m http.server 8099 --bind 127.0.0.1 --directory docs/api/site
```

Abrir `http://127.0.0.1:8099/index.html` y detener el servidor con Ctrl+C. El documento JSON y los recursos
JS/CSS se sirven localmente. No hace falta iniciar PostgreSQL ni proporcionar JWT o la clave de staging.
No abrir el HTML con file://: los navegadores restringen fetch de archivos.
El mensaje «No se pudo cargar la referencia. Utilice un servidor HTTP estático local» se reproduce en
esa apertura directa; la comprobación HTTP debe renderizar las operaciones, no ese mensaje.

Seguridad de consulta:

- `supportedSubmitMethods: []`, `tryItOutEnabled: false` y sin botones de ejecución/autorización.
- `queryConfigEnabled: false`: un parámetro URL no sustituye la copia ni activa peticiones.
- `validatorUrl: null`, recursos vendorizados y CSP sin conectividad externa.
- Interceptor rechaza solicitudes Swagger Client; el único fetch de inicialización lee JSON estático local.
- Servidor público `https://api.example.invalid`, sin staging ni direcciones de infraestructura privada.
- Solo operaciones /api/v1 de producto. No se exponen Actuator, administración u outbox internos.
- No tokens, ejemplos reales ni valores de claves en los artefactos.

La especificación técnica mantiene origen relativo `/` para el equipo; la pública cambia solo el servidor
por uno inerte. Se conservan las 50 operaciones de producto actuales, porque ninguna es una operación
administrativa interna. Health y gestión operativa quedan fuera del contrato de producto, documentados
en las guías de entorno. Preparar el sitio no lo publica ni modifica Swagger/Render existentes.

## Prueba real de navegador

Requiere Node con WebSocket integrado y Edge/Chrome ya instalado; no instala paquetes npm ni navegadores:

```powershell
node --experimental-websocket scripts/check-static-docs.mjs
# Diagnóstico adicional de la apertura directa en el navegador local:
node --experimental-websocket scripts/check-static-docs.mjs --check-file-preview
```

El script sirve únicamente los archivos estáticos en localhost, utiliza un perfil de navegador temporal,
navega rutas/parámetros/modelos y audita solicitudes. Verifica que una URL de configuración no habilite
Try it out; cero solicitudes operativas o externas. Cierra su navegador y limpia solo su perfil temporal.
En Windows usa Edge; en CI utiliza Chrome ya disponible en el runner. ANITEC_DOCS_BROWSER puede señalar
otra instalación autorizada. En Linux CI el sandbox de **ese navegador efímero** se desactiva por compatibilidad
del runner; no cambia la seguridad de Spring ni permite conexiones a proveedores.

La evidencia y capturas se guardan en target y no se versionan. Backend CI conserva Maven y añade esta
comprobación. [Licencia, origen e integridad de los recursos](site/vendor/SOURCE.md).

Referencia de opciones, sin depender de red para usar el sitio:
[documentación oficial de Swagger UI](https://swagger.io/docs/open-source-tools/swagger-ui/usage/configuration/).
Una publicación pública posterior requiere autorización, hosting estático separado y revisión de cabeceras
de seguridad; este trabajo no realiza despliegue alguno.
