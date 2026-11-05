# Email verification proof and coordinated V18 rollout

EMAIL_VERIFICATION_HMAC_KEY is mandatory before startup, including development and disabled
email/password recovery. It must be 32 random bytes Base64, stable across all instances,
independent of JWT, SESSION_RECOVERY_KEY, PASSWORD_RECOVERY_HMAC_KEY and EMAIL_OUTBOX_ENCRYPTION_KEY.
Missing, malformed, wrong-length or equivalent keys fail before Flyway. Operators must provision
this externally when authorized; no runtime fallback/generation or production secret is supplied.
Tests inject a process-scoped random fixture exclusively from src/test; it is not packaged in the backend.

The account stores HMAC-SHA-256 over `email-verification:v1`, account UUID, emission UUID and
six ASCII digits (including leading zeroes), separated by NUL. Constant-time comparison is
performed on 32-byte proofs. A code exists transiently only during issuance and in the encrypted
outbox payload required for transport. Account reconstitution never recovers a code.

V18 deliberately invalidates all pre-V18 codes, without HMAC keys in SQL. It preserves accounts,
password hashes, sessions/tokens, relationships, attempts, resend counts, window and cooldown
timestamps. It cancels only live EMAIL_VERIFICATION jobs and erases their ciphertext/nonce/lease;
accepted/other terminal metadata and all Password Recovery jobs are unchanged. It sends no
emails, grants no free issuance and performs no quota refunds. A pending user may need an
authenticated resend; exhausted users must wait for their existing 24-hour window/cooldown.
A working mail channel is necessary for actual user recovery; disabled delivery still reports
UNCONFIRMED, retains pending status and never claims a send. Password Recovery remains disabled.

The V17 emission UUID is now assigned by the aggregate before computing the proof and persisted
atomically. V18 replaces V17's automatic trigger, which otherwise could change that UUID after
signing. Workers use current emission/proof presence/status/expiry; no readable account code
is used. Account locks still serialize confirmation and resend. Outbox retries, encryption,
leases, idempotency, expiry and terminal cleanup remain unchanged.

verification_code remains temporarily NULL with a SQL constraint forbidding all non-NULL writes.
The current JPA mapping no longer reads/writes it. No active business dependency requires the
column; retaining the explicit old-writer rejection/auditable empty column is the approved
transition strategy, not binary compatibility. A later migration may drop it after review of
external SQL consumers and coordinated version retirement. Immutable V1-V17 remain untouched.
Historical V17 tests intentionally target V17; new V18 tests verify the changed upgrade policy.

## Future deployment: explicit authorization required

1. Provision the same stable HMAC key securely for all new instances before starting them.
2. Plan maintenance/DDL locks and an authorized backup/restore or forward-fix procedure.
3. Stop old instances and workers; allow in-flight operations to finish. An HTTP request already
   accepted by Resend cannot be recalled, but its legacy code will be invalid after the cut.
4. Apply V18 and start only the new binary. Do not mix old/new writers. No persistent environment
   was migrated by this task; anitec-pg and Render must not be touched without authorization.
5. Validate pending-account resend availability and the real mail channel separately. Do not
   activate Resend or Password Recovery implicitly with this migration.

Code-only rollback is unsafe: the old binary writes forbidden plaintext and cannot validate
new proofs. Do not edit applied migrations, delete Flyway history or use repair as an undo.
Prefer a reviewed forward-fix migration; full pre-cut snapshot restoration with its matching
binary requires separate authorization and coordinated session/data consistency checks.
Invalidated/erased code payloads cannot be recovered from HMAC. Backups may still contain old
plaintext, but those codes cannot verify against V18. No key ring/automatic key rotation is
implemented: changing the HMAC key invalidates outstanding proofs and needs its own transition.

## Focused security review

Public response DTOs expose neither verification proof nor code. Verification request diagnostic
strings (including Password Recovery confirmation) are redacted without changing JSON fields.
Normal public errors and configured logs are regression-tested without codes or keys.
SQL show-sql remains off; arbitrary HTTP wire logging, JDBC bind TRACE or operator debug dumps
must not be enabled for security payloads. Their hypothetical deployment use is not evidence
of a current production leak. No breach is claimed.

Security codes have no remaining direct EmailSender path: issuance stages via SecurityEmailDelivery.
The bounded memory queue only holds pre-issuance recovery requests, never committed email delivery.
Direct EmailSender remains necessary for existing veterinary invitations and is not removed.
