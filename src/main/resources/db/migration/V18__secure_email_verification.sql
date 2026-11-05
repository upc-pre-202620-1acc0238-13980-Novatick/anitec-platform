-- Coordinated version cut: no old writers/workers may remain running.
-- No key, code generation or mail delivery belongs in a migration.
DROP TRIGGER identity_verification_emission_identity ON identity_accounts;
DROP FUNCTION identity_verification_emission_identity();

ALTER TABLE identity_accounts ADD COLUMN verification_verifier BYTEA;
UPDATE identity_accounts SET verification_code=NULL WHERE verification_code IS NOT NULL;
ALTER TABLE identity_accounts ADD CONSTRAINT identity_verification_no_plaintext CHECK(verification_code IS NULL);
ALTER TABLE identity_accounts ADD CONSTRAINT identity_verification_proof_shape CHECK(
    verification_verifier IS NULL OR
    (octet_length(verification_verifier)=32 AND verification_attempts<5 AND verification_sent_at IS NOT NULL AND verification_expires_at IS NOT NULL));
CREATE UNIQUE INDEX identity_verification_emission_unique ON identity_accounts(verification_emission_id);

-- All pre-V18 verification work refers to deliberately invalidated legacy proof.
-- Accepted/other terminal metadata and every Password Recovery job remain untouched.
UPDATE identity_email_outbox SET state='CANCELLED',ciphertext=NULL,nonce=NULL,
    claim_id=NULL,claim_until=NULL,error_category='LEGACY_INVALIDATED',
    completed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP
WHERE purpose='EMAIL_VERIFICATION' AND state IN ('PENDING','PROCESSING','RETRY_WAIT');

CREATE FUNCTION identity_email_verification_proof_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.verification_verifier IS NOT NULL AND NEW.verification_verifier IS DISTINCT FROM OLD.verification_verifier
       AND NEW.verification_emission_id=OLD.verification_emission_id THEN
        RAISE EXCEPTION 'Replacement verification proof requires a new emission';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER identity_email_verification_proof_guard BEFORE UPDATE ON identity_accounts
    FOR EACH ROW EXECUTE FUNCTION identity_email_verification_proof_guard();

CREATE OR REPLACE FUNCTION identity_email_eligible(a UUID,p VARCHAR,e UUID,t TIMESTAMPTZ)
RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    SELECT EXISTS(SELECT 1 FROM identity_accounts x WHERE x.id=a AND
       ((p='EMAIL_VERIFICATION' AND x.status='PENDIENTE' AND x.verified_at IS NULL
         AND x.verification_emission_id=e AND x.verification_verifier IS NOT NULL
         AND x.verification_attempts<5 AND x.verification_expires_at>t)
        OR (p='PASSWORD_RECOVERY' AND x.status='ACTIVA' AND x.verified_at IS NOT NULL
         AND EXISTS(SELECT 1 FROM identity_password_recovery r WHERE r.account_id=a AND r.emission_id=e
           AND r.code_verifier IS NOT NULL AND r.consumed_at IS NULL AND r.invalidated_at IS NULL
           AND r.failed_attempts<5 AND r.expires_at>t))))
$$;
