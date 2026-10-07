-- V1: Identity and Access bounded context (spec section 7.1)
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE identity_accounts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email VARCHAR(150) UNIQUE NOT NULL,           -- stored lower-case
    password_hash VARCHAR(255) NOT NULL,
    name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    photo_url TEXT,
    address VARCHAR(255),
    dni VARCHAR(15),
    phone VARCHAR(20),
    role VARCHAR(20) NOT NULL CHECK (role IN ('GANADERO','VETERINARIO','ADMIN')),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDIENTE' CHECK (status IN ('PENDIENTE','ACTIVA','SUSPENDIDA')),
    verification_code VARCHAR(6),
    verification_expires_at TIMESTAMP WITH TIME ZONE,
    verification_attempts INT NOT NULL DEFAULT 0,
    verification_resends INT NOT NULL DEFAULT 0,
    verification_sent_at TIMESTAMP WITH TIME ZONE,
    verified_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE identity_refresh_tokens (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    account_id UUID NOT NULL REFERENCES identity_accounts(id) ON DELETE CASCADE,
    token_digest VARCHAR(64) UNIQUE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_refresh_tokens_account ON identity_refresh_tokens(account_id);
