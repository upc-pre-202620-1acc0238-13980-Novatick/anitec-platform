-- No normalization or renaming of existing animals: incompatible data requires explicit review.
LOCK TABLE livestock_animals IN ACCESS EXCLUSIVE MODE;
DO $$
BEGIN
    IF EXISTS (SELECT owner_id, upper(btrim(code)) FROM livestock_animals
               GROUP BY owner_id, upper(btrim(code)) HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'ANIMAL_CODE_DUPLICATES: review owner-scoped codes before V13; no codes were changed';
    END IF;
    IF EXISTS (SELECT 1 FROM livestock_animals
               WHERE code !~ '^[A-Z0-9-]{1,30}$' OR code !~ '[A-Z0-9]') THEN
        RAISE EXCEPTION 'ANIMAL_CODE_INCOMPATIBLE: existing codes must already be canonical ASCII labels; no codes were changed';
    END IF;
END $$;

ALTER TABLE livestock_animals DROP CONSTRAINT unique_code_per_farm;
ALTER TABLE livestock_animals ALTER COLUMN code TYPE VARCHAR(30);
ALTER TABLE livestock_animals ADD CONSTRAINT ck_animal_code_format
    CHECK (code ~ '^[A-Z0-9-]{1,30}$' AND code ~ '[A-Z0-9]');
ALTER TABLE livestock_animals ADD CONSTRAINT uq_livestock_animal_owner_code UNIQUE (owner_id, code);
ALTER TABLE livestock_animals ADD CONSTRAINT uq_livestock_owner_animal UNIQUE (owner_id, id);
ALTER TABLE livestock_animals ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE livestock_animals DROP CONSTRAINT livestock_animals_sex_check;
ALTER TABLE livestock_animals ALTER COLUMN sex TYPE VARCHAR(11);
ALTER TABLE livestock_animals ADD CONSTRAINT livestock_animals_sex_check
    CHECK (sex IN ('MACHO','HEMBRA','DESCONOCIDO'));

CREATE TABLE livestock_animal_codes (
    owner_id UUID NOT NULL,
    code VARCHAR(30) NOT NULL CHECK (code ~ '^[A-Z0-9-]{1,30}$' AND code ~ '[A-Z0-9]'),
    animal_id UUID NOT NULL,
    reserved_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_animal_codes_owner_code PRIMARY KEY (owner_id, code),
    CONSTRAINT fk_reserved_animal FOREIGN KEY (owner_id, animal_id)
        REFERENCES livestock_animals(owner_id, id) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX idx_reserved_codes_animal ON livestock_animal_codes(animal_id);
INSERT INTO livestock_animal_codes(owner_id, code, animal_id)
    SELECT owner_id, code, id FROM livestock_animals;

CREATE TABLE livestock_code_counters (
    owner_id UUID PRIMARY KEY,
    next_value BIGINT NOT NULL DEFAULT 1 CHECK (next_value > 0)
);
INSERT INTO livestock_code_counters(owner_id) SELECT DISTINCT owner_id FROM livestock_animals;

CREATE TABLE livestock_code_changes (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    animal_id UUID NOT NULL REFERENCES livestock_animals(id),
    old_code VARCHAR(30) NOT NULL,
    new_code VARCHAR(30) NOT NULL,
    author_id UUID NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK (old_code <> new_code)
);
CREATE INDEX idx_animal_code_changes ON livestock_code_changes(animal_id, changed_at);

CREATE FUNCTION livestock_protect_code_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Animal code reservations and history are immutable' USING ERRCODE='23514';
END $$;
CREATE TRIGGER protect_reserved_codes BEFORE UPDATE OR DELETE ON livestock_animal_codes
    FOR EACH ROW EXECUTE FUNCTION livestock_protect_code_history();
CREATE TRIGGER protect_code_changes BEFORE UPDATE OR DELETE ON livestock_code_changes
    FOR EACH ROW EXECUTE FUNCTION livestock_protect_code_history();

CREATE FUNCTION livestock_reserve_animal_code() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE reserved_animal UUID;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.id IS DISTINCT FROM OLD.id OR NEW.owner_id IS DISTINCT FROM OLD.owner_id
           OR NEW.farm_id IS DISTINCT FROM OLD.farm_id THEN
            RAISE EXCEPTION 'Animal identity and ownership are immutable' USING ERRCODE='23514';
        END IF;
        IF NEW.code IS DISTINCT FROM OLD.code AND OLD.status <> 'ACTIVO' THEN
            RAISE EXCEPTION 'Inactive animal codes cannot change' USING ERRCODE='23514';
        END IF;
        NEW.version := OLD.version + 1;
    END IF;
    IF TG_OP = 'INSERT' OR NEW.code IS DISTINCT FROM OLD.code THEN
        INSERT INTO livestock_animal_codes(owner_id, code, animal_id)
            VALUES (NEW.owner_id, NEW.code, NEW.id) ON CONFLICT DO NOTHING;
        SELECT animal_id INTO reserved_animal FROM livestock_animal_codes
            WHERE owner_id=NEW.owner_id AND code=NEW.code;
        IF reserved_animal IS DISTINCT FROM NEW.id THEN
            RAISE EXCEPTION 'duplicate key value violates unique constraint "uq_animal_codes_owner_code"'
                USING ERRCODE='23505', CONSTRAINT='uq_animal_codes_owner_code';
        END IF;
        IF TG_OP = 'UPDATE' THEN
            -- Only the owning farmer can edit through the API; its UUID is the audit author.
            INSERT INTO livestock_code_changes(animal_id, old_code, new_code, author_id)
                VALUES (NEW.id, OLD.code, NEW.code, NEW.owner_id);
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER reserve_animal_code BEFORE INSERT OR UPDATE ON livestock_animals
    FOR EACH ROW EXECUTE FUNCTION livestock_reserve_animal_code();
