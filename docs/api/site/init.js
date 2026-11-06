"use strict";
(async function () {
  // This sole network read is a local static artifact, never a live /v3/api-docs endpoint.
  const response = await fetch("./openapi-public.json", { credentials: "omit", cache: "no-store" });
  if (!response.ok) throw new Error("No se pudo cargar la referencia estática");
  const spec = await response.json();
  window.ui = SwaggerUIBundle({
    spec,
    dom_id: "#swagger-ui",
    layout: "BaseLayout",
    deepLinking: true,
    filter: true,
    docExpansion: "list",
    defaultModelsExpandDepth: 1,
    defaultModelExpandDepth: 1,
    displayOperationId: false,
    tagsSorter: "alpha",
    operationsSorter: "alpha",
    displayRequestDuration: false,
    showExtensions: true,
    supportedSubmitMethods: [],
    tryItOutEnabled: false,
    queryConfigEnabled: false,
    persistAuthorization: false,
    validatorUrl: null,
    // Defense in depth if a future UI regression attempts an operation or remote resolution.
    requestInterceptor: function () { throw new Error("Consulta académica: ejecución deshabilitada"); },
    plugins: [function () { return { wrapComponents: {
      authorizeBtn: function () { return function () { return null; }; },
      authorizeOperationBtn: function () { return function () { return null; }; }
    } }; }]
  });
})().catch(function () {
  document.querySelector("#swagger-ui").textContent = "No se pudo cargar la referencia. Utilice un servidor HTTP estático local.";
});
