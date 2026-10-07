-- V2: Livestock Management bounded context (spec section 7.2)
CREATE TABLE livestock_species (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(100) UNIQUE NOT NULL,
    description TEXT,
    photo_url TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE livestock_farms (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    farmer_id UUID NOT NULL,                      -- -> identity_accounts.id (no FK, cross-context)
    name VARCHAR(150) NOT NULL,
    location VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE livestock_animals (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    farm_id UUID NOT NULL REFERENCES livestock_farms(id) ON DELETE CASCADE,
    owner_id UUID NOT NULL,                       -- -> identity_accounts.id (denormalized, no FK)
    species_id UUID NOT NULL REFERENCES livestock_species(id),
    code VARCHAR(50) NOT NULL,                    -- e.g. BOV-024
    name VARCHAR(100),
    breed VARCHAR(100),
    sex VARCHAR(10) NOT NULL CHECK (sex IN ('MACHO','HEMBRA')),
    birth_date DATE CHECK (birth_date IS NULL OR birth_date <= CURRENT_DATE),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVO'
        CHECK (status IN ('ACTIVO','INACTIVO','VENDIDO','FALLECIDO')),
    photo_url TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT unique_code_per_farm UNIQUE (farm_id, code)   -- includes inactive animals
);
CREATE INDEX idx_animals_farm ON livestock_animals(farm_id);
CREATE INDEX idx_animals_owner_status ON livestock_animals(owner_id, status);
CREATE INDEX idx_animals_search ON livestock_animals(farm_id, code, name);
CREATE INDEX idx_animals_species ON livestock_animals(farm_id, species_id);

CREATE TABLE livestock_observations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    animal_id UUID NOT NULL REFERENCES livestock_animals(id) ON DELETE CASCADE,
    author_id UUID NOT NULL,                      -- -> identity_accounts.id (farmer only)
    text TEXT NOT NULL CHECK (btrim(text) <> ''),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_observations_animal ON livestock_observations(animal_id, created_at DESC);

CREATE TABLE livestock_inventory_capacity (
    owner_id UUID PRIMARY KEY,                    -- -> identity_accounts.id (farmer)
    allowed_animals INT NOT NULL,
    active_animals INT NOT NULL DEFAULT 0,
    last_plan_revision BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
