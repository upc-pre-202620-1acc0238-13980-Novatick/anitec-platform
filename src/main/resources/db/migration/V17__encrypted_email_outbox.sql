-- Existing accounts/codes remain unchanged. An opaque identity tracks replacement, not the code value.
ALTER TABLE identity_accounts ADD COLUMN verification_emission_id UUID NOT NULL DEFAULT gen_random_uuid();
CREATE FUNCTION identity_verification_emission_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.verification_code IS DISTINCT FROM OLD.verification_code
       OR NEW.verification_sent_at IS DISTINCT FROM OLD.verification_sent_at THEN
        NEW.verification_emission_id := gen_random_uuid();
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER identity_verification_emission_identity BEFORE UPDATE ON identity_accounts
    FOR EACH ROW EXECUTE FUNCTION identity_verification_emission_identity();

CREATE TABLE identity_email_outbox (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES identity_accounts(id) ON DELETE CASCADE,
    purpose VARCHAR(30) NOT NULL CHECK (purpose IN ('EMAIL_VERIFICATION','PASSWORD_RECOVERY')),
    emission_id UUID NOT NULL,
    state VARCHAR(20) NOT NULL CHECK(state IN ('PENDING','PROCESSING','RETRY_WAIT','ACCEPTED','CANCELLED','EXPIRED','FAILED','UNKNOWN')),
    ciphertext BYTEA,
    nonce BYTEA UNIQUE,
    crypto_version SMALLINT NOT NULL DEFAULT 1 CHECK(crypto_version=1),
    attempts INT NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 5),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    claim_id UUID UNIQUE,
    claim_until TIMESTAMPTZ,
    idempotency_key VARCHAR(64) NOT NULL UNIQUE,
    provider_id UUID,
    error_category VARCHAR(40),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    UNIQUE(purpose,emission_id),
    CHECK(expires_at>created_at AND expires_at<=created_at+interval '24 hours'),
    CHECK(idempotency_key='anitec-email/'||id::text),
    CHECK((state IN ('PENDING','PROCESSING','RETRY_WAIT') AND ciphertext IS NOT NULL AND octet_length(ciphertext)>=16
              AND octet_length(nonce)=12 AND completed_at IS NULL)
       OR (state IN ('ACCEPTED','CANCELLED','EXPIRED','FAILED','UNKNOWN') AND ciphertext IS NULL AND nonce IS NULL AND completed_at IS NOT NULL)),
    CHECK((state='PROCESSING' AND claim_id IS NOT NULL AND claim_until IS NOT NULL)
       OR (state<>'PROCESSING' AND claim_id IS NULL AND claim_until IS NULL)),
    CHECK(state<>'ACCEPTED' OR provider_id IS NOT NULL),
    CHECK(state<>'PENDING' OR attempts=0),
    CHECK(state<>'PROCESSING' OR attempts BETWEEN 1 AND 5),
    CHECK(state<>'RETRY_WAIT' OR attempts BETWEEN 1 AND 4)
);
CREATE INDEX identity_email_outbox_due ON identity_email_outbox(next_attempt_at) WHERE state IN ('PENDING','RETRY_WAIT','PROCESSING');
CREATE INDEX identity_email_outbox_retention ON identity_email_outbox(completed_at) WHERE completed_at IS NOT NULL;

-- A single database-wide pause coordinates provider credential/domain/quota failures across workers.
CREATE TABLE identity_email_delivery_gate (
    id SMALLINT PRIMARY KEY CHECK(id=1), paused_until TIMESTAMPTZ NOT NULL,
    error_category VARCHAR(40)
);
INSERT INTO identity_email_delivery_gate VALUES(1,'-infinity',NULL);

CREATE FUNCTION identity_email_outbox_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state IN ('ACCEPTED','CANCELLED','EXPIRED','FAILED','UNKNOWN') THEN
        RAISE EXCEPTION 'Terminal email job cannot be changed';
    END IF;
    IF (OLD.state IN ('PENDING','RETRY_WAIT') AND NEW.state NOT IN ('PROCESSING','CANCELLED','EXPIRED'))
       OR (OLD.state='PROCESSING' AND NEW.state NOT IN ('PROCESSING','RETRY_WAIT','ACCEPTED','CANCELLED','EXPIRED','FAILED','UNKNOWN')) THEN
        RAISE EXCEPTION 'Invalid email job transition';
    END IF;
    IF NEW.id<>OLD.id OR NEW.account_id<>OLD.account_id OR NEW.purpose<>OLD.purpose
       OR NEW.emission_id<>OLD.emission_id OR NEW.expires_at<>OLD.expires_at
       OR NEW.created_at<>OLD.created_at OR NEW.idempotency_key<>OLD.idempotency_key
       OR NEW.crypto_version<>OLD.crypto_version OR NEW.attempts<OLD.attempts
       OR (NEW.ciphertext IS NOT NULL AND NEW.ciphertext IS DISTINCT FROM OLD.ciphertext)
       OR (NEW.nonce IS NOT NULL AND NEW.nonce IS DISTINCT FROM OLD.nonce) THEN
        RAISE EXCEPTION 'Immutable email job identity or payload';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER identity_email_outbox_guard BEFORE UPDATE ON identity_email_outbox
    FOR EACH ROW EXECUTE FUNCTION identity_email_outbox_guard();

CREATE FUNCTION identity_email_eligible(a UUID, p VARCHAR, e UUID, t TIMESTAMPTZ)
RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    SELECT EXISTS(SELECT 1 FROM identity_accounts x WHERE x.id=a AND
       ((p='EMAIL_VERIFICATION' AND x.status='PENDIENTE' AND x.verified_at IS NULL
         AND x.verification_emission_id=e AND x.verification_code IS NOT NULL
         AND x.verification_attempts<5 AND x.verification_expires_at>t)
        OR (p='PASSWORD_RECOVERY' AND x.status='ACTIVA' AND x.verified_at IS NOT NULL
         AND EXISTS(SELECT 1 FROM identity_password_recovery r WHERE r.account_id=a AND r.emission_id=e
           AND r.code_verifier IS NOT NULL AND r.consumed_at IS NULL AND r.invalidated_at IS NULL
           AND r.failed_attempts<5 AND r.expires_at>t))))
$$;
