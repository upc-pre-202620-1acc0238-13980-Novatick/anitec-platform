# Recursos locales de Swagger UI

Swagger UI **5.17.14**, la misma versión que resuelve Springdoc 2.6.0 en este proyecto.
`swagger-ui-bundle.js` y `swagger-ui.css` se extrajeron sin modificación del WebJar
`org.webjars:swagger-ui:5.17.14`. No se agregó una dependencia Maven ni se carga una CDN.

- Origen: https://github.com/swagger-api/swagger-ui/tree/v5.17.14
- Licencia oficial: [LICENSE](LICENSE), obtenida del mismo tag; se conservan los avisos del bundle.
- [checksums.json](checksums.json) registra SHA-256 de los recursos y licencia.
- La configuración de consulta está en `../init.js`; no utiliza el initializer de ejemplo de Swagger.
- Referencia oficial de opciones: https://swagger.io/docs/open-source-tools/swagger-ui/usage/configuration/

No actualizar estos archivos de forma silenciosa: una actualización requiere revisión de licencia,
integridad y prueba de navegador sin solicitudes operativas. Vendorizarlos evita dependencias de red
al consultar el sitio; no demuestra ausencia absoluta de vulnerabilidades en una biblioteca de terceros.
