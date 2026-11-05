# Security email delivery

Delivery is disabled by default (`email.delivery.mode=disabled` / `EMAIL_DELIVERY_MODE=disabled`).
Password recovery remains independently disabled by default. This change does not enable it.
No provider credentials or encryption keys belong in the repository or CI.

## Enabled configuration

An operator must separately provision `RESEND_API_KEY`, `RESEND_FROM` (an address under
`notifications.vitalwatch.space`) and `EMAIL_OUTBOX_ENCRYPTION_KEY` (32 random bytes,
Base64). The encryption key must differ from `SESSION_RECOVERY_KEY` and
`PASSWORD_RECOVERY_HMAC_KEY`. Startup validates enabled configuration regardless of
active profile combinations. Local validation does not prove provider/domain authorization.
Do not rotate the outbox key while encrypted jobs remain pending: pause workers, drain or
explicitly cancel pending jobs through a reviewed operational procedure first. There is no
public outbox administration endpoint and no key-rotation/key-ring feature in this release.

Timeout defaults: connection 3000 ms, response read 10000 ms; configurable with
`EMAIL_CONNECT_TIMEOUT_MS` / `EMAIL_RESPONSE_TIMEOUT_MS`. Default claim lease is 60 s
(`EMAIL_CLAIM_LEASE_SECONDS`), greater than both timeouts plus a margin. A claim is fenced:
only its current owner, before expiry, can record a result. No database transaction or lock
remains open during HTTP. Multiple backend instances may claim different jobs with
`FOR UPDATE SKIP LOCKED`.

## Persistence and contracts

V17 adds the encrypted outbox, a shared provider pause gate, and an opaque verification
emission identity. It does not rewrite codes, accounts or historic migrations. Emission/quota
changes and the email job commit or roll back together. Unique purpose/emission identity
prevents duplicate jobs. Workers never generate codes or consume user quotas.

Registration and authenticated verification resend return `UNCONFIRMED`: durable staging
is not provider acceptance. They preserve existing REST/account states. Disabled delivery
creates no email job and never claims `SENT`; a registered account remains pending.
A staging infrastructure failure rolls back the registration/resend and returns sanitized
`503 EMAIL_UNAVAILABLE`. Existing account uniqueness/verification policies remain in force.

Password recovery still requires its existing explicit enablement, HMAC and origin controls.
Its bounded queue only accepts asynchronous **requests before issuance**, preserving the
existing generic response and account lookup separation. Such an unprocessed request can
still be lost at restart, without consuming quota or confirming a code. Once issuance commits,
its encrypted email is durable; the memory queue no longer owns committed email delivery.
Recovery confirmation and global session revocation are unchanged. Veterinary invitations
retain their direct, single-attempt delivery contract and are not migrated to this outbox.

## Encryption and templates

The immutable UTF-8 JSON HTTP payload contains exactly `from`, `to`, `subject`, `html`, `text`.
AES-256-GCM uses a random 96-bit nonce and 128-bit tag, with version/job/emission/purpose AAD.
Recipient, subject, code and message content are not duplicated in plaintext outbox columns.
Existing account email and legacy verification-code storage are not redesigned by V17;
outbox encryption does not imply encryption of all account data or backups.

Security templates cover verification/recovery in Spanish and English. Existing registration
and recovery contracts have no language preference: current emissions use Spanish. No account
schema, endpoint or general i18n contract was added for language selection.

## Outcomes and retries

`PENDING -> PROCESSING -> ACCEPTED | RETRY_WAIT | FAILED | UNKNOWN`.
Live jobs may become `CANCELLED` or `EXPIRED`; terminal jobs cannot be reactivated.
Provider acceptance requires a valid email UUID. It does not prove inbox delivery or reading.
No webhooks or exactly-once delivery guarantee are provided.

At most five technical attempts (including the first), configurable downward with
`EMAIL_MAX_ATTEMPTS`. Backoff is 5/15/45/120 seconds plus 0–1 second jitter, respecting
`Retry-After`. A stable opaque `anitec-email/<job UUID>` key and identical serialized body are
reused. No retry extends a code, changes quotas or crosses code expiry/provider's 24-hour
idempotency window. Provider 5xx/rate-limit/concurrent-idempotency errors retry; payload
rejection/idempotency mismatch fail terminally. Invalid credentials/domain authorization
pause all workers for at least five minutes (or a longer `Retry-After`). Global quota exhaustion pauses affected delivery for
`Retry-After` or conservatively 24 hours. The latter expires short-lived code jobs instead
of sending them late. An implausible provider pause longer than one year fails closed with a persistent pause requiring operator review. Timeouts/disconnections/missing success IDs are uncertain and become
`UNKNOWN` if another safe attempt is unavailable.

Every attempt rechecks current account/code eligibility. A replacement, consumption,
exhaustion or suspension prevents subsequent attempts immediately, independently of cleanup.
A request already sent to the provider cannot be recalled; concurrent account changes, expiry
or a lost lease may leave acceptance unknown locally. Stable provider idempotency limits
repetition within its retention window; it cannot prove exactly once at the mailbox.

Terminal ciphertext/nonce are erased. Operational metadata is purged in batches of at most 500 after 14 days by the
worker janitor (also when delivery is disabled). Backups/WAL may retain older ciphertext.
Workers poll every `EMAIL_POLL_MS` (1000 ms) and process up to `EMAIL_BATCH_SIZE` (10) per pass.
Graceful shutdown stops new claims; an interrupted in-flight operation is recovered after its
lease and remains subject to the same key, payload, expiry and attempt limit. Logs expose
job IDs and fixed error categories only, never provider bodies, codes or credentials.
