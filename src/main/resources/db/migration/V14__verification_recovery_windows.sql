-- Register a one-time policy transition without replacing accounts, codes, or attempts.
ALTER TABLE identity_accounts ADD COLUMN verification_window_started_at TIMESTAMPTZ;
ALTER TABLE identity_accounts ADD COLUMN verification_policy_migrated_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- The initial emission is provable only when it still has zero resends and its timestamp
-- matches account creation. A last-emission timestamp alone cannot reconstruct old windows.
UPDATE identity_accounts
SET verification_window_started_at = verification_sent_at
WHERE status = 'PENDIENTE' AND verification_resends = 0
  AND verification_sent_at = created_at;

-- Other legacy rows keep their counters/code/attempts and known cooldown unchanged.
-- A NULL window starts once, on the first accepted resend, consuming resend number one.
ALTER TABLE identity_accounts ADD CONSTRAINT ck_verification_recovery_counters
    CHECK (verification_resends BETWEEN 0 AND 5 AND verification_attempts BETWEEN 0 AND 5);
