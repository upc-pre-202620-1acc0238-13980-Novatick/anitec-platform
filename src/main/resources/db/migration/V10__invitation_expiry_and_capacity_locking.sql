-- V10: expired invitation state + optimistic locking on the capacity tables
--
-- 1) An expired PENDIENTE invitation was a dead end: it still occupied
--    uq_invitation_pending (so re-sending always returned 409), it was hidden
--    from the veterinarian's pending list and could not be answered either.
--    LinkingInvitation.expireIfDue() now lazily moves it to VENCIDA, which
--    frees the partial unique index for a fresh invitation.
ALTER TABLE linking_invitations DROP CONSTRAINT linking_invitations_status_check;
ALTER TABLE linking_invitations
    ADD CONSTRAINT linking_invitations_status_check
    CHECK (status IN ('PENDIENTE','ACEPTADA','RECHAZADA','VENCIDA'));

-- 2) Optimistic locking (JPA @Version) for the plan-limit counters: they are
--    read-modified-written on every animal registration/status change and on
--    every invitation acceptance/revocation, so concurrent updates must fail
--    loudly instead of silently overwriting each other.
ALTER TABLE livestock_inventory_capacity ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE linking_capacity ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
