# ANITEC — Etapa 15A: aceptación funcional integral

Autorización: Team Leader, 2026-11-06. Primera PR de la etapa 15 exclusivamente. Base: PR #15 integrada
en develop mediante `412cae0dbef47aad6d31cbc6fd19e8ab2f0a0c2e`, con 535 pruebas de referencia.

Esta matriz conecta los recorridos aprobados con pruebas ejecutables. No sustituye la documentación API
definitiva ni afirma cobertura de todas las combinaciones de negocio, dispositivos o despliegues.
Los códigos US citados pertenecen a la trazabilidad ya presente en el código; esta PR no reconstruye un
backlog académico externo ni certifica prototipos que no formen parte de sus pruebas.

## Estrategia

Se conservan todas las pruebas anteriores. Se añaden cinco casos en MvpFunctionalAcceptanceTests y uno
en el fixture existente StagingAccessGateIntegrationTests. Los componentes de negocio, transacciones,
seguridad y PostgreSQL son reales. Las peticiones de los recorridos se realizan por HTTP localhost.

La configuración de aceptación activa recuperación y simulación solo en test. Resend queda deshabilitado.
Un SecurityEmailDelivery controlado captura códigos en memoria después del commit; no los lee de PostgreSQL.
El intake de recuperación se drena explícitamente para ejecutar el procesamiento entre request y confirm.
No demuestra la planificación asíncrona ni el transporte/outbox en ese fixture: sus garantías se reutilizan
de las pruebas específicas existentes. No se cambia la configuración de ningún entorno persistente.

Los ticks automáticos se cancelan únicamente en el contexto de la clase de aceptación para evitar que un
barrido del reloj mezcle los dos caminos de vencimiento. El caso del scheduler invoca el bean real y verifica
su cron de 30 minutos; no elimina ni modifica el componente productivo ni espera media hora en CI.

## Matriz de trazabilidad

Rutas de clases en `src/test/java/com/anitec/backend/`.

| Recorrido / frontera | Prueba nueva o reutilizada | Evidencia y límites |
| --- | --- | --- |
| Registro, verificación y login de ambos perfiles | MvpFunctionalAcceptanceTests.passwordRecoveryRevokesOnlyOwnersSessionsAndPreservesDomainAccess (GANADERO/VETERINARIO) | Registro/verificación/login HTTP con código obtenido del emisor controlado, seis dígitos; sin entrega real |
| Sesiones independientes, rotación y logout | Mismo caso; SessionSecurityTests.twoLoginsRemainIndependent, logoutOnlyCurrentSession, sameOperationConcurrentGetsOneSuccessor | Logout de una cadena, otra sesión sigue usable; reintento mismo operationId conserva pareja. Concurrencia y expiración detalladas en regresiones existentes |
| Recuperación válida/fallida y acceso al dominio | Mismo caso; PasswordRecoveryTests.successfulResetRequiresNewLoginAndRevokesEverySession, refreshConcurrentWithResetCannotKeepOldSessionActive | Código incorrecto no revoca; correcto revoca todas las sesiones del propietario y no las ajenas; login nuevo recupera acceso a animal propio o vinculado sin cambiar el recurso |
| Recuperación deshabilitada | PasswordRecoveryDisabledTests.requestUnavailableWithoutKeyOrMailerDoesNotCreateIssuance, confirmationUnavailableAndNoNewTokens, existingCodeCannotBeConsumedWhileGloballyDisabled | 503 RECOVERY_UNAVAILABLE y sin efectos. StagingAccessGateIntegrationTests.correctKeyPreservesRegistrationRecoveryAndSwaggerContracts también comprueba 503 bajo staging |
| Fincas e inventario: US01–US06 | MvpFunctionalAcceptanceTests.registeredUsersCompleteClinicalJourneyAndRetainHistoryAfterRevocation | A crea/consulta finca y animal, edita nombre conservando atributos, agrega/consulta observación; B no puede consultar ni modificar |
| Contrato de edición y códigos históricos | Mismo recorrido; MvpContractStabilizationTests.animalDetailRoundTripPreservesUnchangedFieldsAndRejectsStaleVersion; LivestockInvariantTests.historicalReservationsRecoveryAndNoOpAudit, changingVisibleCodePreservesClinicalAndObservationAssociations | Se conservan speciesId, photoUrl, finca, sexo, raza y nacimiento. Baja mantiene historial y reserva del código. Versiones y concurrencia cubiertas por pruebas específicas |
| Invitación, elegibilidad, rechazo y aceptación: US14–US18 | Mismo recorrido; LinkingEligibilityTests; MvpContractStabilizationTests.rejectingInvitationPreservesCapacityAndCanBeInvitedAgain | Pendiente no elegible; rechazo sin acceso; aceptación concede lectura autorizada. Estados suspendidos, cambios posteriores, destinatario y cupos reutilizan regresiones existentes |
| Visita, atención y complementos: US07–US11 | Mismo recorrido; SmokeTests.careVisitsAttentionsInstructionsAndNotifications | Veterinario programa, no completa sin atención, registra tratamiento/vacuna/indicaciones; ganadero consulta el registro completo |
| Control posterior y atención independiente: US12–US13 | Mismo recorrido; VeterinaryCareRegressionTests.concurrentControlAttentionsPreserveOriginAndCreateExactlyOneNewRecord, multipleIndependentAttentionsRemainAllowed | Control tiene atención propia; la segunda atención de la misma cita falla; se conserva la original y la atención independiente |
| Revocación y conservación clínica | Mismo recorrido; VeterinaryCareRegressionTests.revokedLinkAlreadyBlocksNewWritesAndClinicalReadsButPreservesHistory_US07_US12_US13_US18 | Tras revocar, veterinario pierde agenda/lectura/escritura; ganadero conserva historial incluso tras baja del animal |
| Bandeja informativa interna | Mismo recorrido; MvpContractStabilizationTests.visitNoticeIsSelfContainedPrivateAndDoesNotPromiseFarmerAgenda, followUpHasAnInformativeControlNotice | Avisos persistidos pertenecen al destinatario; B no los recibe ni marca como leídos; lectura legítima persiste. No acredita FCM ni entrega push |
| Simulación para ambos roles | SubscriptionSimulationTests.approvedDeclinedRenewedCancelledAndExpiredAreVisibleThroughApi (GANADERO/VETERINARIO) | Preparar no activa; rechazo conserva estado; aprobación, renovación explícita, replay, cancelación y nueva contratación |
| Idempotencia y aislamiento de pagos | SubscriptionSimulationTests.concurrentInitializationsAndConfirmationsHaveOneEffect, conflictingConcurrentOutcomesCannotRewriteTheWinner, concurrentRenewalReplaysCannotAccreditMultiplePeriods, ownershipRoleValidationAndServerAmountsCannotBeBypassed | Un solo efecto por referencia; precio y capacidad del servidor; confirmación ajena rechazada |
| Vencimiento mediante consulta | MvpFunctionalAcceptanceTests.premiumExpiryIsReconciledByReadAndPreservesExcessRecords | Caso independiente: primer reconciliador es GET my-subscription; ambos perfiles pasan a GRATUITO/VENCIDA y propagan 15/3 |
| Vencimiento mediante scheduler sin consulta previa | MvpFunctionalAcceptanceTests.premiumExpiryIsReconciledByScheduledEntryPointWithoutSubscriptionReads | Contador de HTTP comprueba cero consultas de suscripción durante todo el caso; bean real aplica vencimiento, cron 0 */30 * * * *; revisión solo aumenta una vez |
| Exceso conservado y nuevas altas rechazadas | Ambos casos de vencimiento | Fixtures distintos con 16 animales, cuatro vínculos y atención clínica; se conservan datos y lecturas; nueva alta falla con INVENTORY_LIMIT_REACHED/LINKING_LIMIT_REACHED |
| Simulador inaccesible con clave/JWT válidos de staging | StagingAccessGateIntegrationTests.validStagingKeyAndJwtCannotActivateOrRenewSimulatedPaymentsForEitherRole | Ambos roles, prod+staging reales en contexto local temporal: consultas 200, contratación/renovación/APPROVED/DECLINED 503; un pendiente sintético previo sigue PENDING |
| Exclusión de prod y perfiles combinados | ProductionSecurityIntegrationTests.productionMigratesWithoutPrivilegedAccountsAndRejectsCheckoutAndClientApproval; SubscriptionSimulationPolicyTests.forbiddenProfilesNeverExposeClientApproval; PaymentGatewayConfigurationTests | HTTP en prod, selección del gateway en combinaciones y validación de configuración. No peticiones al staging público |
| Persistencia tras reinicio | SubscriptionSimulationRestartTests.bothProfilesRetainAccreditedPeriodsAndResolvedPaymentsAcrossRestart; SessionRestartTests; PasswordRecoveryRestartTests | Contextos recreados conservando el PostgreSQL temporal; sin pretender validar un reinicio/despliegue de Render |
| Correo seguro y es/en | EmailOutboxTests, EmailOutboxRestartTests, EmailLanguageIntegrationTests, EmailLanguageRestartTests | Pruebas específicas conservan HMAC, cifrado, cuotas, idioma, payload e idempotencia con transporte HTTP simulado; no entrega real a buzones |

Los métodos se pueden localizar por nombre en esas clases. Las pruebas individuales mantienen los detalles
de cada frontera; la prueba clínica nueva aporta continuidad entre contextos en lugar de reemplazarlas.

## Control de tiempo y datos

Los recorridos crean recursos y realizan transiciones por API. SQL se limita a aserciones de persistencia,
adelantar el vencimiento almacenado de los fixtures descartables y preparar un pago PENDING sintético en
el contexto staging para demostrar que no puede aprobarse. No se utilizan escrituras SQL para sustituir
aceptación, registro clínico, cancelación, activación o reconciliación. No se recuperan códigos desde SQL.

PostgreSQL 16 de Testcontainers aplica V1–V18 sobre bases temporales. No se modifican migraciones ni se
añade V19. Ninguna prueba se dirige a anitec-pg, una base persistente o Render. No hay API keys reales.

## Límite operativo aceptado de vencimiento

La reconciliación sucede al consultar my-subscription o por el barrido de 30 minutos. Otros contextos
pueden conservar el límite anterior entre vencimiento y reconciliación. La ventana de hasta 30 minutos
supone que el scheduler funciona; una interrupción del backend puede prolongarla. Flutter/Kotlin deben
refrescar la suscripción antes de mostrar capacidades, sin asumir responsabilidad por la autorización:
esa sigue siendo del backend. Estas pruebas acreditan ambos mecanismos sin afirmar precisión instantánea.

## Frontera de esta PR

No se modifican código productivo, contratos, migraciones, flags, proveedores o workflow. No se implementan
Stripe, Google Play Billing, FCM, clientes móviles ni cambios Figma. No se realizan despliegues ni merge.

La segunda PR de etapa 15 queda pendiente de autorización: OpenAPI versionada, Swagger público **de consulta**,
guía común Flutter/Kotlin y renovación integral del README raíz. El README deberá revisarse y reorganizarse
por completo, retirar información obsoleta y documentar producto, arquitectura, requisitos, instalación,
variables sin secretos, perfiles, módulos, simulación, limitaciones y enlaces a guías; no solo añadir párrafos.
Este documento registra la exigencia, pero no inicia esa implementación.

## Ejecución

```powershell
.\mvnw.cmd -B '-Dtest=MvpFunctionalAcceptanceTests,StagingAccessGateIntegrationTests' test
.\mvnw.cmd -B test
```

Requiere Java 21 y Docker Engine accesible. Los resultados definitivos locales y de Backend CI se registran
en la descripción de la PR y en el informe externo de validación, evitando copiar logs sensibles al repositorio.
