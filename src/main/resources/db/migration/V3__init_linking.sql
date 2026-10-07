-- V3: Veterinary Linking bounded context (spec section 7.3)
CREATE TABLE linking_invitations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    farmer_id UUID NOT NULL,                      -- -> identity_accounts.id (no FK)
    vet_email VARCHAR(150) NOT NULL,
    vet_id UUID,                                  -- -> identity_accounts.id (resolved recipient)
    status VARCHAR(20) NOT NULL DEFAULT 'PENDIENTE'
        CHECK (status IN ('PENDIENTE','ACEPTADA','RECHAZADA')),
    email_delivery VARCHAR(20) CHECK (email_delivery IS NULL OR email_delivery IN ('SENT','UNCONFIRMED')),
    sent_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    responded_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_invitations_vet_status ON linking_invitations(vet_id, status);
CREATE UNIQUE INDEX uq_invitation_pending ON linking_invitations(farmer_id, vet_id) WHERE status = 'PENDIENTE';

CREATE TABLE linking_links (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    invitation_id UUID REFERENCES linking_invitations(id),
    farmer_id UUID NOT NULL,                      -- -> identity_accounts.id
    vet_id UUID NOT NULL,                         -- -> identity_accounts.id
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVA' CHECK (status IN ('ACTIVA','REVOCADA')),
    linked_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP WITH TIME ZONE
);
CREATE UNIQUE INDEX uq_active_link ON linking_links(farmer_id, vet_id) WHERE status = 'ACTIVA';
CREATE INDEX idx_links_vet_active ON linking_links(vet_id, status);
CREATE INDEX idx_links_farmer_active ON linking_links(farmer_id, status);

CREATE TABLE linking_capacity (
    vet_id UUID PRIMARY KEY,                      -- -> identity_accounts.id
    allowed_ranchers INT NOT NULL,
    active_links INT NOT NULL DEFAULT 0,
    last_plan_revision BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
