# ANITEC staging access gate

Activate **`SPRING_PROFILES_ACTIVE=prod,staging`**. Staging alone fails before beans or Flyway.
Production validations remain active, including mixed profiles. There is no gate opt-out flag.

Configure **`STAGING_ACCESS_KEY`** (property `staging.access.key`) privately: exactly 32 independently
generated cryptographically random bytes encoded as canonical standard Base64 (44 characters,
ending in `=`). No whitespace, URL-safe encoding or omitted padding. Missing, malformed,
degenerate or reused JWT/session/verification/recovery/outbox keys fail startup without printing values.
The application validates format/length/separation, not the provenance or entropy of an arbitrary key;
the operator must use a secure random generator. Never commit keys or put them in URLs or build arguments.

Send exactly one **`X-ANITEC-Staging-Key`** header over HTTPS. Use the same canonical representation.
Missing, empty, malformed, incorrect, repeated or comma-merged headers return generic HTTP 403
`STAGING_ACCESS_DENIED` before CORS, JWT, Identity or controllers. HTTP syntax rejected by the
server/firewall itself may instead receive its own 400; it never becomes authorized.

Only exact **`GET /actuator/health`** is exempt; subpaths, trailing slashes and other methods are not.
Health remains real (including database health), not a synthetic unconditional UP. In staging,
health details/components and probes are disabled, only health is exposed over Actuator, and
Swagger/OpenAPI are disabled. This privacy policy takes precedence over property overrides.
The service must use the root context and port supplied by the platform for the documented health path.

The gate is an additional shared laboratory barrier, **not a user login**. Continue sending the normal
JWT in `Authorization: Bearer ...` on private routes. A correct gate key without JWT still gets 401;
a JWT without the staging key gets 403; existing role and session controls still apply.
Outside staging the filter passes through without validating a staging key or changing contracts.

Keep first-staging integrations off: `EMAIL_DELIVERY_MODE=disabled`,
`EMAIL_DELIVERY_WORKER_ENABLED=false`, `IDENTITY_PASSWORD_RECOVERY_ENABLED=false`,
`PAYMENTS_SIMULATION_ENABLED=false`, `DEMO_SEED=false`. The staging profile supplies those defaults;
do not override them for the initial deployment. Continue configuring independent `JWT_SECRET`,
`SESSION_RECOVERY_KEY`, `EMAIL_VERIFICATION_HMAC_KEY` and the isolated PostgreSQL connection.
There are no new database tables, migrations, dependencies or administrative endpoints.

Use a private API-client secret variable rather than literal commands, captures or checked-in
collections. Do not enable request/header tracing in the application, proxy or client. The backend
does not log this header; third-party logging must also redact it. Rotation replaces the configured
key and requires restarting instances; coordinate rotation as no old-key grace window is provided.
Browser CORS preflights do not send the secret header value and are intentionally denied; use a
private API client for this laboratory. Do not embed the shared staging key in public web code or an APK.

The public URL and exact health route remain reachable. This gate does not prevent network-volume
attacks, replace future rate limits or provide per-person identity. Suspend the laboratory if the key
leaks or persistent anomalous traffic/restarts occur. Deployment and merge need separate approval.
