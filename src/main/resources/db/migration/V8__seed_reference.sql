-- V8: Reference data - species catalog and plans (spec section 12)
INSERT INTO livestock_species (id, name, description) VALUES
    ('a0000000-0000-4000-8000-000000000001', 'Bovino',  'Ganado bovino: leche y carne (Holstein, Angus, Brahman)'),
    ('a0000000-0000-4000-8000-000000000002', 'Ovino',   'Ganado ovino: lana y carne'),
    ('a0000000-0000-4000-8000-000000000003', 'Caprino', 'Ganado caprino: leche y carne'),
    ('a0000000-0000-4000-8000-000000000004', 'Porcino', 'Ganado porcino: carne'),
    ('a0000000-0000-4000-8000-000000000005', 'Equino',  'Ganado equino: monta y trabajo')
ON CONFLICT DO NOTHING;

-- Decision #15: farmer 15/150 active animals, vet 3/25 farmer links, PEN prices.
INSERT INTO subscriptions_plans
    (id, name, profile, plan_type, price, currency, billing_period, capacity_limit, active) VALUES
    ('b0000000-0000-4000-8000-000000000001', 'Plan Gratuito Ganadero',   'GANADERO',    'GRATUITO', 0.00,   'PEN', 'MENSUAL', 15,  TRUE),
    ('b0000000-0000-4000-8000-000000000002', 'Plan Premium Ganadero',    'GANADERO',    'PREMIUM',  19.90,  'PEN', 'MENSUAL', 150, TRUE),
    ('b0000000-0000-4000-8000-000000000003', 'Plan Gratuito Veterinario','VETERINARIO', 'GRATUITO', 0.00,   'PEN', 'MENSUAL', 3,   TRUE),
    ('b0000000-0000-4000-8000-000000000004', 'Plan Premium Veterinario', 'VETERINARIO', 'PREMIUM',  39.90,  'PEN', 'MENSUAL', 25,  TRUE)
ON CONFLICT DO NOTHING;
