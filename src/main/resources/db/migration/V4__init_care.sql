-- V4: Veterinary Care bounded context (spec section 7.4)
CREATE TABLE care_appointments (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    animal_id UUID NOT NULL,                      -- -> livestock_animals.id (no FK, cross-context)
    owner_id UUID NOT NULL,                       -- -> identity_accounts.id (farmer)
    veterinarian_id UUID NOT NULL,                -- -> identity_accounts.id
    type VARCHAR(10) NOT NULL CHECK (type IN ('VISITA','CONTROL')),
    scheduled_at TIMESTAMP WITH TIME ZONE NOT NULL CHECK (scheduled_at > CURRENT_TIMESTAMP),
    reason VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'PROGRAMADA'
        CHECK (status IN ('PROGRAMADA','COMPLETADA','CANCELADA')),
    origin_attention_id UUID,                     -- -> care_records.id (US12 control origin)
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_appointments_vet_date ON care_appointments(veterinarian_id, scheduled_at);
CREATE INDEX idx_appointments_owner ON care_appointments(owner_id, scheduled_at);

CREATE TABLE care_records (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    animal_id UUID NOT NULL,                      -- -> livestock_animals.id (no FK)
    owner_id UUID NOT NULL,                       -- -> identity_accounts.id
    veterinarian_id UUID NOT NULL,                -- -> identity_accounts.id
    appointment_id UUID REFERENCES care_appointments(id) ON DELETE SET NULL,
    title VARCHAR(150) NOT NULL,
    description TEXT,
    attention_date DATE NOT NULL CHECK (attention_date <= CURRENT_DATE),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_care_records_animal ON care_records(animal_id, attention_date DESC);
CREATE INDEX idx_care_records_vet ON care_records(veterinarian_id, attention_date DESC);

CREATE TABLE care_treatments (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    care_record_id UUID NOT NULL REFERENCES care_records(id) ON DELETE CASCADE,
    description TEXT NOT NULL CHECK (btrim(description) <> ''),
    applied_date DATE NOT NULL CHECK (applied_date <= CURRENT_DATE),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE care_vaccinations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    care_record_id UUID NOT NULL REFERENCES care_records(id) ON DELETE CASCADE,
    name VARCHAR(150) NOT NULL,
    applied_date DATE NOT NULL CHECK (applied_date <= CURRENT_DATE),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE care_instruction_versions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    care_record_id UUID NOT NULL REFERENCES care_records(id) ON DELETE CASCADE,
    content TEXT NOT NULL CHECK (btrim(content) <> ''),
    author_id UUID NOT NULL,                      -- -> identity_accounts.id (vet)
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_instructions_record ON care_instruction_versions(care_record_id, created_at DESC);
