-- V9: Account seeds (spec section 12).
--  * Admin account is always created (e-mail from placeholder, password from
--    ADMIN_SEED_PASSWORD_HASH or the local fallback).
--  * Demo users + demo flow are guarded by the ${demo_seed} placeholder
--    (true locally, false in production).
DO $$
DECLARE
    v_admin_hash TEXT;
    v_now TIMESTAMP WITH TIME ZONE := CURRENT_TIMESTAMP;
    v_farmer UUID := 'd0000000-0000-4000-8000-000000000001';
    v_vet UUID := 'd0000000-0000-4000-8000-000000000002';
    v_farm UUID := 'd0000000-0000-4000-8000-000000000003';
    v_bovino UUID := 'a0000000-0000-4000-8000-000000000001';
    v_ovino UUID := 'a0000000-0000-4000-8000-000000000002';
    v_free_farmer UUID := 'b0000000-0000-4000-8000-000000000001';
    v_free_vet UUID := 'b0000000-0000-4000-8000-000000000003';
BEGIN
    -- ---------------------------------------------------------------- admin
    IF '${admin_password_hash}' = '' THEN
        v_admin_hash := '$2a$10$vUzdquWkqHPLUv0TfT5g8.5wRjjz4uPrLAFt8ITX..QZnZzZbCoYS'; -- Anitec!2026Admin (local fallback)
    ELSE
        v_admin_hash := '${admin_password_hash}';
    END IF;

    INSERT INTO identity_accounts
        (id, email, password_hash, name, last_name, role, status, verified_at, created_at, updated_at)
    VALUES
        ('d0000000-0000-4000-8000-0000000000ff', LOWER('${admin_email}'), v_admin_hash,
         'Admin', 'ANITEC', 'ADMIN', 'ACTIVA', v_now, v_now, v_now)
    ON CONFLICT (email) DO NOTHING;

    -- ------------------------------------------------------------ demo data
    IF '${demo_seed}' = 'true' THEN
        -- Demo ganadero (password: Anitec!Demo123)
        INSERT INTO identity_accounts
            (id, email, password_hash, name, last_name, phone, role, status, verified_at, created_at, updated_at)
        VALUES
            (v_farmer, 'demo.ganadero@anitec.pe',
             '$2a$10$JaFRy3Ke1Z2HHMraFZDGmeipk40G879LR0gITdS2XleoKC5rRKLwu',
             'Luis', 'Martínez', '999888777', 'GANADERO', 'ACTIVA', v_now, v_now, v_now)
        ON CONFLICT (email) DO NOTHING;

        -- Demo veterinario (password: Anitec!Demo123)
        INSERT INTO identity_accounts
            (id, email, password_hash, name, last_name, phone, role, status, verified_at, created_at, updated_at)
        VALUES
            (v_vet, 'demo.veterinario@anitec.pe',
             '$2a$10$JaFRy3Ke1Z2HHMraFZDGmeipk40G879LR0gITdS2XleoKC5rRKLwu',
             'María', 'López', '999777666', 'VETERINARIO', 'ACTIVA', v_now, v_now, v_now)
        ON CONFLICT (email) DO NOTHING;

        -- Free subscriptions (revision 1) - what AccountRegistered would create
        INSERT INTO subscriptions_subscriptions
            (id, account_id, profile, plan_id, plan_type, status, renewal_enabled,
             allowed_capacity, revision, started_at, updated_at)
        VALUES
            ('c0000000-0000-4000-8000-000000000001', v_farmer, 'GANADERO', v_free_farmer,
             'GRATUITO', 'ACTIVA', TRUE, 15, 1, v_now, v_now),
            ('c0000000-0000-4000-8000-000000000002', v_vet, 'VETERINARIO', v_free_vet,
             'GRATUITO', 'ACTIVA', TRUE, 3, 1, v_now, v_now)
        ON CONFLICT (account_id) DO NOTHING;

        -- Capacities (3 active animals for the farmer, 1 active link for the vet)
        INSERT INTO livestock_inventory_capacity
            (owner_id, allowed_animals, active_animals, last_plan_revision, updated_at)
        VALUES (v_farmer, 15, 3, 1, v_now)
        ON CONFLICT (owner_id) DO NOTHING;

        INSERT INTO linking_capacity (vet_id, allowed_ranchers, active_links, last_plan_revision, updated_at)
        VALUES (v_vet, 3, 1, 1, v_now)
        ON CONFLICT (vet_id) DO NOTHING;

        -- Farm + animals + observation
        INSERT INTO livestock_farms (id, farmer_id, name, location, created_at, updated_at)
        VALUES (v_farm, v_farmer, 'Fundo Demo', 'Chiclayo, Lambayeque', v_now, v_now)
        ON CONFLICT (id) DO NOTHING;

        INSERT INTO livestock_animals
            (id, farm_id, owner_id, species_id, code, name, breed, sex, birth_date, status, created_at, updated_at)
        VALUES
            ('e0000000-0000-4000-8000-000000000001', v_farm, v_farmer, v_bovino, 'BOV-001', 'Luna', 'Holstein', 'HEMBRA', '2021-03-12', 'ACTIVO', v_now, v_now),
            ('e0000000-0000-4000-8000-000000000002', v_farm, v_farmer, v_bovino, 'BOV-002', 'Rayo', 'Brahman', 'MACHO', '2022-01-20', 'ACTIVO', v_now, v_now),
            ('e0000000-0000-4000-8000-000000000003', v_farm, v_farmer, v_ovino, 'OV-001', 'Mora', 'Corriedale', 'HEMBRA', '2021-09-05', 'ACTIVO', v_now, v_now)
        ON CONFLICT (id) DO NOTHING;

        INSERT INTO livestock_observations (id, animal_id, author_id, text, created_at)
        VALUES ('e0000000-0000-4000-8000-000000000010',
                'e0000000-0000-4000-8000-000000000001', v_farmer,
                'Se adapta bien al lote y mantiene buen apetito.', v_now)
        ON CONFLICT (id) DO NOTHING;

        -- Vet linkage: accepted invitation + active link
        INSERT INTO linking_invitations
            (id, farmer_id, vet_email, vet_id, status, email_delivery, sent_at, expires_at, responded_at)
        VALUES ('f0000000-0000-4000-8000-000000000001', v_farmer, 'demo.veterinario@anitec.pe',
                v_vet, 'ACEPTADA', 'SENT', v_now, v_now + INTERVAL '7 days', v_now)
        ON CONFLICT (id) DO NOTHING;

        INSERT INTO linking_links (id, invitation_id, farmer_id, vet_id, status, linked_at)
        VALUES ('f0000000-0000-4000-8000-000000000002', 'f0000000-0000-4000-8000-000000000001',
                v_farmer, v_vet, 'ACTIVA', v_now)
        ON CONFLICT (id) DO NOTHING;

        -- Scheduled visit + attention with treatment, vaccination and instructions
        INSERT INTO care_appointments
            (id, animal_id, owner_id, veterinarian_id, type, scheduled_at, reason, status, created_at)
        VALUES ('f0000000-0000-4000-8000-000000000003', 'e0000000-0000-4000-8000-000000000001',
                v_farmer, v_vet, 'VISITA', v_now + INTERVAL '2 days', 'Control general',
                'PROGRAMADA', v_now)
        ON CONFLICT (id) DO NOTHING;

        INSERT INTO care_records
            (id, animal_id, owner_id, veterinarian_id, title, description, attention_date, created_at)
        VALUES ('f0000000-0000-4000-8000-000000000004', 'e0000000-0000-4000-8000-000000000001',
                v_farmer, v_vet, 'Control general',
                'Se realizó revisión preventiva completa y control de peso.',
                CURRENT_DATE, v_now)
        ON CONFLICT (id) DO NOTHING;

        INSERT INTO care_treatments (id, care_record_id, description, applied_date, created_at)
        VALUES ('f0000000-0000-4000-8000-000000000005', 'f0000000-0000-4000-8000-000000000004',
                'Vitamina B12 inyectable 5ml', CURRENT_DATE, v_now)
        ON CONFLICT (id) DO NOTHING;

        INSERT INTO care_vaccinations (id, care_record_id, name, applied_date, created_at)
        VALUES ('f0000000-0000-4000-8000-000000000006', 'f0000000-0000-4000-8000-000000000004',
                'Aftosa', CURRENT_DATE, v_now)
        ON CONFLICT (id) DO NOTHING;

        INSERT INTO care_instruction_versions (id, care_record_id, content, author_id, created_at)
        VALUES ('f0000000-0000-4000-8000-000000000007', 'f0000000-0000-4000-8000-000000000004',
                'Mantener en aislamiento por 48 horas e hidratación constante.', v_vet, v_now)
        ON CONFLICT (id) DO NOTHING;
    END IF;
END $$;
