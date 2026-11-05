-- Password recovery is a separate purpose, with no account reconstruction or verification changes.
CREATE TABLE identity_password_recovery (
    account_id UUID PRIMARY KEY REFERENCES identity_accounts(id) ON DELETE CASCADE,
    purpose VARCHAR(30) NOT NULL CHECK (purpose = 'PASSWORD_RECOVERY'),
    window_started_at TIMESTAMPTZ NOT NULL,
    emission_count INT NOT NULL CHECK (emission_count BETWEEN 1 AND 5),
    last_issued_at TIMESTAMPTZ NOT NULL,
    emission_id UUID NOT NULL UNIQUE,
    code_verifier BYTEA CHECK (code_verifier IS NULL OR octet_length(code_verifier) = 32),
    expires_at TIMESTAMPTZ NOT NULL,
    failed_attempts INT NOT NULL CHECK (failed_attempts BETWEEN 0 AND 5),
    consumed_at TIMESTAMPTZ,
    invalidated_at TIMESTAMPTZ,
    CHECK (last_issued_at >= window_started_at AND last_issued_at < window_started_at + interval '24 hours'),
    CHECK (expires_at = last_issued_at + interval '10 minutes'),
    CHECK ((code_verifier IS NOT NULL AND consumed_at IS NULL AND invalidated_at IS NULL AND failed_attempts < 5)
        OR (code_verifier IS NULL AND ((consumed_at IS NOT NULL AND invalidated_at IS NULL AND failed_attempts < 5)
                                   OR (invalidated_at IS NOT NULL AND consumed_at IS NULL)))),
    CHECK (consumed_at IS NULL OR consumed_at >= last_issued_at),
    CHECK (invalidated_at IS NULL OR invalidated_at >= last_issued_at)
);
