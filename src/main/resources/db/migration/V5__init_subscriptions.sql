-- V5: Subscriptions bounded context (spec section 7.5)
CREATE TABLE subscriptions_plans (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(50) NOT NULL,
    profile VARCHAR(20) NOT NULL CHECK (profile IN ('GANADERO','VETERINARIO')),
    plan_type VARCHAR(20) NOT NULL CHECK (plan_type IN ('GRATUITO','PREMIUM')),
    price NUMERIC(10,2) NOT NULL DEFAULT 0,
    currency VARCHAR(3) NOT NULL DEFAULT 'PEN',
    billing_period VARCHAR(20) NOT NULL DEFAULT 'MENSUAL',
    capacity_limit INT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE subscriptions_subscriptions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    account_id UUID UNIQUE NOT NULL,              -- -> identity_accounts.id (no FK)
    profile VARCHAR(20) NOT NULL CHECK (profile IN ('GANADERO','VETERINARIO')),
    plan_id UUID NOT NULL REFERENCES subscriptions_plans(id),
    plan_type VARCHAR(20) NOT NULL CHECK (plan_type IN ('GRATUITO','PREMIUM')),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVA' CHECK (status IN ('ACTIVA','VENCIDA','CANCELADA')),
    renewal_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    allowed_capacity INT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ends_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_subscriptions_account ON subscriptions_subscriptions(account_id);

CREATE TABLE subscriptions_payments (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    subscription_id UUID NOT NULL REFERENCES subscriptions_subscriptions(id),
    account_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    gateway_ref VARCHAR(100) UNIQUE NOT NULL,     -- idempotency key (TS02)
    amount NUMERIC(10,2) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'PEN',
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','APPROVED','DECLINED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
