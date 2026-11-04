-- Veterinary Care: preserve independent attentions (NULL) and allow at most one per appointment.
-- Freeze writes while checking legacy data and installing the constraint in Flyway's transaction.
LOCK TABLE care_appointments, care_records IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (
        SELECT appointment_id FROM care_records
        WHERE appointment_id IS NOT NULL
        GROUP BY appointment_id HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'APPOINTMENT_ATTENTION_DUPLICATES: review historical records before V12; no data was changed';
    END IF;

    IF EXISTS (
        SELECT a.id FROM care_appointments a
        LEFT JOIN care_records r ON r.appointment_id = a.id
        GROUP BY a.id, a.status
        HAVING (a.status = 'COMPLETADA' AND count(r.id) <> 1)
            OR (a.status <> 'COMPLETADA' AND count(r.id) <> 0)
    ) THEN
        RAISE EXCEPTION 'APPOINTMENT_ATTENTION_STATE_CONFLICT: review historical appointment states before V12; no data was changed';
    END IF;
END $$;

ALTER TABLE care_records
    ADD CONSTRAINT uq_care_records_appointment UNIQUE (appointment_id);
