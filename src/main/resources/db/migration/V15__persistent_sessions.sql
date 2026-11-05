-- Preserve accounts and business data; legacy tokens require a new login.
CREATE TABLE identity_sessions (
 id UUID PRIMARY KEY,
 account_id UUID NOT NULL REFERENCES identity_accounts(id) ON DELETE CASCADE,
 created_at TIMESTAMPTZ NOT NULL,
 expires_at TIMESTAMPTZ NOT NULL,
 revoked_at TIMESTAMPTZ,
 CHECK (expires_at > created_at AND expires_at <= created_at + interval '168 hours')
);
CREATE INDEX idx_identity_sessions_account ON identity_sessions(account_id);
CREATE TABLE identity_session_tokens (
 token_digest VARCHAR(64) PRIMARY KEY CHECK (token_digest ~ '^[0-9a-f]{64}$'),
 session_id UUID NOT NULL REFERENCES identity_sessions(id) ON DELETE CASCADE,
 created_at TIMESTAMPTZ NOT NULL,
 expires_at TIMESTAMPTZ NOT NULL,
 consumed_at TIMESTAMPTZ,
 operation_id UUID,
 successor_digest VARCHAR(64) UNIQUE,
 recovery_ciphertext BYTEA,
 recovery_until TIMESTAMPTZ,
 UNIQUE(session_id,operation_id),
 UNIQUE(session_id,token_digest),
 FOREIGN KEY(session_id,successor_digest) REFERENCES identity_session_tokens(session_id,token_digest),
 CHECK ((consumed_at IS NULL AND operation_id IS NULL AND successor_digest IS NULL) OR
        (consumed_at IS NOT NULL AND operation_id IS NOT NULL AND successor_digest IS NOT NULL)),
 CHECK (recovery_ciphertext IS NULL OR consumed_at IS NOT NULL),
 CHECK (successor_digest IS NULL OR successor_digest <> token_digest),
 CHECK (expires_at > created_at)
);
CREATE INDEX idx_identity_session_tokens_session ON identity_session_tokens(session_id);
-- Retain legacy digests as evidence; they are never accepted by the session service.
UPDATE identity_refresh_tokens SET revoked_at=coalesce(revoked_at,CURRENT_TIMESTAMP);
-- Coordinate direct persistence changes too, under the account row lock acquired by UPDATE.
CREATE FUNCTION identity_revoke_sensitive_sessions() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.password_hash IS DISTINCT FROM OLD.password_hash OR NEW.email IS DISTINCT FROM OLD.email
    OR NEW.role IS DISTINCT FROM OLD.role OR NEW.status IS DISTINCT FROM OLD.status
    OR (OLD.verified_at IS NOT NULL AND NEW.verified_at IS NULL) THEN
   UPDATE identity_sessions SET revoked_at=coalesce(revoked_at,CURRENT_TIMESTAMP) WHERE account_id=NEW.id;
   UPDATE identity_session_tokens SET recovery_ciphertext=NULL,recovery_until=NULL
     WHERE session_id IN (SELECT id FROM identity_sessions WHERE account_id=NEW.id);
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER identity_sensitive_session_revocation AFTER UPDATE ON identity_accounts
 FOR EACH ROW EXECUTE FUNCTION identity_revoke_sensitive_sessions();

-- Enforce the absolute session bound even for an alternate persistence writer.
CREATE FUNCTION identity_bound_session_token() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.expires_at > (SELECT expires_at FROM identity_sessions WHERE id=NEW.session_id) THEN
   RAISE EXCEPTION 'SESSION_TOKEN_EXPIRY_EXCEEDS_SESSION' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER identity_session_token_expiry BEFORE INSERT OR UPDATE OF expires_at,session_id
 ON identity_session_tokens FOR EACH ROW EXECUTE FUNCTION identity_bound_session_token();
