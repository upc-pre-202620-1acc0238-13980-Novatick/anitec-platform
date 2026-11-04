-- Retire the historical third role. V1-V10 remain immutable.
-- Flyway runs this transaction before Hibernate and before serving HTTP.
CREATE TEMP TABLE retired_accounts ON COMMIT DROP AS
    SELECT id FROM identity_accounts WHERE role = 'ADMIN';
CREATE TEMP TABLE retired_animals ON COMMIT DROP AS
    SELECT id FROM livestock_animals
    WHERE owner_id IN (SELECT id FROM retired_accounts)
       OR farm_id IN (SELECT id FROM livestock_farms WHERE farmer_id IN (SELECT id FROM retired_accounts));
CREATE TEMP TABLE retired_appointments ON COMMIT DROP AS
    SELECT id FROM care_appointments
    WHERE owner_id IN (SELECT id FROM retired_accounts)
       OR veterinarian_id IN (SELECT id FROM retired_accounts)
       OR animal_id IN (SELECT id FROM retired_animals);
CREATE TEMP TABLE retired_records ON COMMIT DROP AS
    SELECT id FROM care_records
    WHERE owner_id IN (SELECT id FROM retired_accounts)
       OR veterinarian_id IN (SELECT id FROM retired_accounts)
       OR animal_id IN (SELECT id FROM retired_animals);

-- Explicit cross-context cleanup: most of these references have no FK.
DELETE FROM care_instruction_versions WHERE author_id IN (SELECT id FROM retired_accounts);
UPDATE care_appointments SET origin_attention_id = NULL
    WHERE origin_attention_id IN (SELECT id FROM retired_records);
DELETE FROM care_records WHERE id IN (SELECT id FROM retired_records);
-- Treatments, vaccinations and remaining instruction versions cascade from records.
DELETE FROM care_appointments WHERE id IN (SELECT id FROM retired_appointments);
-- Records attached to these appointments retain their history via ON DELETE SET NULL.
DELETE FROM livestock_observations WHERE author_id IN (SELECT id FROM retired_accounts);
DELETE FROM livestock_animals WHERE id IN (SELECT id FROM retired_animals);
DELETE FROM livestock_farms WHERE farmer_id IN (SELECT id FROM retired_accounts);
DELETE FROM livestock_inventory_capacity WHERE owner_id IN (SELECT id FROM retired_accounts);

DELETE FROM linking_links
    WHERE farmer_id IN (SELECT id FROM retired_accounts)
       OR vet_id IN (SELECT id FROM retired_accounts)
       OR invitation_id IN (SELECT id FROM linking_invitations
           WHERE farmer_id IN (SELECT id FROM retired_accounts) OR vet_id IN (SELECT id FROM retired_accounts));
DELETE FROM linking_invitations
    WHERE farmer_id IN (SELECT id FROM retired_accounts) OR vet_id IN (SELECT id FROM retired_accounts);
DELETE FROM linking_capacity WHERE vet_id IN (SELECT id FROM retired_accounts);
DELETE FROM subscriptions_payments
    WHERE account_id IN (SELECT id FROM retired_accounts)
       OR subscription_id IN (SELECT id FROM subscriptions_subscriptions
           WHERE account_id IN (SELECT id FROM retired_accounts));
DELETE FROM subscriptions_subscriptions WHERE account_id IN (SELECT id FROM retired_accounts);
DELETE FROM notification_notifications WHERE user_id IN (SELECT id FROM retired_accounts);
-- No other table references this obsolete audit log; do not use DROP ... CASCADE.
DROP TABLE admin_audit_log;
DELETE FROM identity_accounts WHERE id IN (SELECT id FROM retired_accounts);
-- identity_refresh_tokens cascade through their account FK.

-- Reconcile surviving capacity counters after removal of historical test relations.
UPDATE livestock_inventory_capacity c SET active_animals =
    (SELECT count(*) FROM livestock_animals a WHERE a.owner_id = c.owner_id AND a.status = 'ACTIVO');
UPDATE linking_capacity c SET active_links =
    (SELECT count(*) FROM linking_links l WHERE l.vet_id = c.vet_id AND l.status = 'ACTIVA');
ALTER TABLE identity_accounts DROP CONSTRAINT identity_accounts_role_check;
ALTER TABLE identity_accounts ADD CONSTRAINT identity_accounts_role_check
    CHECK (role IN ('GANADERO', 'VETERINARIO'));
