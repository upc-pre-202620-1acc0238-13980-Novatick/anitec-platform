-- =============================================================================
-- SEMILLA DEMO AMPLIADA para entornos donde Flyway ya corrió con DEMO_SEED=false
-- (p. ej. Render en producción).
--
--   Origen : src/main/resources/db/migration/V9__seed_accounts.sql (bloque demo)
--   Uso    : pegar en la consola de Render (anitec-db > Data > Connect > Open in
--            Console) o en psql con la External Database URL, y ejecutar.
--
-- Idempotente: TODOS los INSERT usan ON CONFLICT ... DO NOTHING, así que se puede
-- ejecutar tantas veces como haga falta sin duplicar filas ni tocar datos
-- existentes. No modifica V9 ni su checksum en flyway_schema_history.
--
-- El usuario admin NO está aquí: lo siembra siempre la migración V9.
--
-- -----------------------------------------------------------------------------
-- CUENTAS (todas con contraseña "Anitec!Demo123", salvo el admin de V9):
--
--   demo.ganadero@anitec.pe      GANADERO     ACTIVA      Plan gratuito (8/15 animales)
--   demo.veterinario@anitec.pe   VETERINARIO  ACTIVA      Plan gratuito (2/3 vínculos)
--   demo.ganadero2@anitec.pe     GANADERO     ACTIVA      PREMIUM 150 (4 animales)
--   demo.veterinario2@anitec.pe  VETERINARIO  ACTIVA      PREMIUM 25 (1 vínculo)
--   demo.ganadero3@anitec.pe     GANADERO     ACTIVA      Plan gratuito (2/15 animales)
--   demo.suspendido@anitec.pe    GANADERO     SUSPENDIDA  Para probar el bloqueo de login
--
-- CONTENIDO: 4 fincas · 15 animales de las 5 especies (1 VENDIDO) · 8 observaciones
-- · 8 invitaciones (2 PENDIENTE, 4 ACEPTADA, 1 RECHAZADA, 1 VENCIDA) · 4 vínculos
-- (1 REVOCADA) · 8 citas (4 futuras PROGRAMADA, 1 futura CANCELADA, 2 pasadas
-- COMPLETADA + 1 pasada CANCELADA) · 6 historias clínicas con tratamientos, vacunas
-- e instrucciones versionadas (2 versiones) · 3 pagos (2 APPROVED + 1 DECLINED) ·
-- 11 notificaciones · 7 entradas de auditoría de admin.
--
-- Los contadores de capacidad (animales activos / vínculos activos) se recalculan
-- al final del script para que siempre coincidan con las filas reales.
-- =============================================================================

DO $$
DECLARE
    v_now TIMESTAMP WITH TIME ZONE := CURRENT_TIMESTAMP;

    -- ------------------------------------------------------------- cuentas
    v_farmer     UUID := 'd0000000-0000-4000-8000-000000000001'; -- demo.ganadero
    v_vet        UUID := 'd0000000-0000-4000-8000-000000000002'; -- demo.veterinario
    v_farmer2    UUID := 'd0000000-0000-4000-8000-000000000010'; -- demo.ganadero2 (premium)
    v_vet2       UUID := 'd0000000-0000-4000-8000-000000000011'; -- demo.veterinario2 (premium)
    v_farmer3    UUID := 'd0000000-0000-4000-8000-000000000012'; -- demo.ganadero3
    v_suspended  UUID := 'd0000000-0000-4000-8000-000000000013'; -- demo.suspendido

    -- ------------------------------------------------ especies (seed V8)
    v_bovino  UUID := 'a0000000-0000-4000-8000-000000000001';
    v_ovino   UUID := 'a0000000-0000-4000-8000-000000000002';
    v_caprino UUID := 'a0000000-0000-4000-8000-000000000003';
    v_porcino UUID := 'a0000000-0000-4000-8000-000000000004';
    v_equino  UUID := 'a0000000-0000-4000-8000-000000000005';

    -- -------------------------------------------------- planes (seed V8)
    v_free_farmer UUID := 'b0000000-0000-4000-8000-000000000001'; -- 15 animales
    v_prem_farmer UUID := 'b0000000-0000-4000-8000-000000000002'; -- 150 animales
    v_free_vet    UUID := 'b0000000-0000-4000-8000-000000000003'; -- 3 vínculos
    v_prem_vet    UUID := 'b0000000-0000-4000-8000-000000000004'; -- 25 vínculos

    -- ------------------------------------------------------------- fincas
    v_farm  UUID := 'd0000000-0000-4000-8000-000000000003'; -- Fundo Demo (ganadero 1)
    v_farm2 UUID := 'd0000000-0000-4000-8000-000000000020'; -- Los Álamos (ganadero 1)
    v_farm3 UUID := 'd0000000-0000-4000-8000-000000000021'; -- Granja San José (ganadero 2)
    v_farm4 UUID := 'd0000000-0000-4000-8000-000000000022'; -- Estancia La Vega (ganadero 3)

    -- Misma contraseña que V9: "Anitec!Demo123"
    v_demo_hash TEXT := '$2a$10$JaFRy3Ke1Z2HHMraFZDGmeipk40G879LR0gITdS2XleoKC5rRKLwu';
BEGIN
    -- =========================================================== 1. cuentas
    -- Demo ganadero (existente en V9)
    INSERT INTO identity_accounts
        (id, email, password_hash, name, last_name, phone, role, status, verified_at, created_at, updated_at)
    VALUES
        (v_farmer, 'demo.ganadero@anitec.pe', v_demo_hash,
         'Luis', 'Martínez', '999888777', 'GANADERO', 'ACTIVA', v_now, v_now, v_now)
    ON CONFLICT (email) DO NOTHING;

    -- Demo veterinario (existente en V9)
    INSERT INTO identity_accounts
        (id, email, password_hash, name, last_name, phone, role, status, verified_at, created_at, updated_at)
    VALUES
        (v_vet, 'demo.veterinario@anitec.pe', v_demo_hash,
         'María', 'López', '999777666', 'VETERINARIO', 'ACTIVA', v_now, v_now, v_now)
    ON CONFLICT (email) DO NOTHING;

    -- Segundo ganadero (cuenta premium)
    INSERT INTO identity_accounts
        (id, email, password_hash, name, last_name, phone, dni, address, role, status, verified_at, created_at, updated_at)
    VALUES
        (v_farmer2, 'demo.ganadero2@anitec.pe', v_demo_hash,
         'José', 'Ramírez', '999666555', '45678912', 'Jr. Comercio 456, Chiclayo',
         'GANADERO', 'ACTIVA', v_now - INTERVAL '30 days', v_now - INTERVAL '30 days', v_now - INTERVAL '3 days')
    ON CONFLICT (email) DO NOTHING;

    -- Segundo veterinario (cuenta premium)
    INSERT INTO identity_accounts
        (id, email, password_hash, name, last_name, phone, dni, address, role, status, verified_at, created_at, updated_at)
    VALUES
        (v_vet2, 'demo.veterinario2@anitec.pe', v_demo_hash,
         'Carlos', 'Mendoza', '999555444', '45123456', 'Av. Circunvalación 789, Chiclayo',
         'VETERINARIO', 'ACTIVA', v_now - INTERVAL '45 days', v_now - INTERVAL '45 days', v_now - INTERVAL '7 days')
    ON CONFLICT (email) DO NOTHING;

    -- Tercer ganadero (plan gratuito, ya vinculado al primer veterinario)
    INSERT INTO identity_accounts
        (id, email, password_hash, name, last_name, phone, dni, address, role, status, verified_at, created_at, updated_at)
    VALUES
        (v_farmer3, 'demo.ganadero3@anitec.pe', v_demo_hash,
         'Ana', 'Torres', '999444333', '44987654', 'Camp. El Rosal s/n, Ferreñafe',
         'GANADERO', 'ACTIVA', v_now - INTERVAL '40 days', v_now - INTERVAL '40 days', v_now - INTERVAL '40 days')
    ON CONFLICT (email) DO NOTHING;

    -- Cuenta suspendida (para demostrar el bloqueo de login y el panel de admin)
    INSERT INTO identity_accounts
        (id, email, password_hash, name, last_name, phone, dni, address, role, status, verified_at, created_at, updated_at)
    VALUES
        (v_suspended, 'demo.suspendido@anitec.pe', v_demo_hash,
         'Pedro', 'Gómez', '999333222', '43876543', 'Psje. Los Pinos 12, Túcume',
         'GANADERO', 'SUSPENDIDA', v_now - INTERVAL '60 days', v_now - INTERVAL '60 days', v_now - INTERVAL '3 days')
    ON CONFLICT (email) DO NOTHING;

    -- ===================================================== 2. suscripciones
    -- Gratuitas (revision 1) - lo que crea AccountRegistered
    INSERT INTO subscriptions_subscriptions
        (id, account_id, profile, plan_id, plan_type, status, renewal_enabled,
         allowed_capacity, revision, started_at, updated_at)
    VALUES
        ('c0000000-0000-4000-8000-000000000001', v_farmer, 'GANADERO', v_free_farmer,
         'GRATUITO', 'ACTIVA', TRUE, 15, 1, v_now, v_now),
        ('c0000000-0000-4000-8000-000000000002', v_vet, 'VETERINARIO', v_free_vet,
         'GRATUITO', 'ACTIVA', TRUE, 3, 1, v_now, v_now)
    ON CONFLICT (account_id) DO NOTHING;

    INSERT INTO subscriptions_subscriptions
        (id, account_id, profile, plan_id, plan_type, status, renewal_enabled,
         allowed_capacity, revision, started_at, ends_at, updated_at)
    VALUES
        -- Premium ganadero: revision 2 (como al confirmar un pago APPROVED)
        ('c0000000-0000-4000-8000-000000000010', v_farmer2, 'GANADERO', v_prem_farmer,
         'PREMIUM', 'ACTIVA', TRUE, 150, 2,
         v_now - INTERVAL '3 days', v_now + INTERVAL '27 days', v_now - INTERVAL '3 days'),
        -- Premium veterinario
        ('c0000000-0000-4000-8000-000000000011', v_vet2, 'VETERINARIO', v_prem_vet,
         'PREMIUM', 'ACTIVA', TRUE, 25, 2,
         v_now - INTERVAL '7 days', v_now + INTERVAL '23 days', v_now - INTERVAL '7 days'),
        -- Gratuitas de las cuentas nuevas
        ('c0000000-0000-4000-8000-000000000012', v_farmer3, 'GANADERO', v_free_farmer,
         'GRATUITO', 'ACTIVA', TRUE, 15, 1, v_now - INTERVAL '40 days', NULL, v_now - INTERVAL '40 days'),
        ('c0000000-0000-4000-8000-000000000013', v_suspended, 'GANADERO', v_free_farmer,
         'GRATUITO', 'ACTIVA', TRUE, 15, 1, v_now - INTERVAL '60 days', NULL, v_now - INTERVAL '3 days')
    ON CONFLICT (account_id) DO NOTHING;

    -- ============================================================ 3. pagos
    INSERT INTO subscriptions_payments
        (id, subscription_id, account_id, plan_id, gateway_ref, amount, currency, status, created_at, updated_at)
    VALUES
        ('90000000-0000-4000-8000-000000000001', 'c0000000-0000-4000-8000-000000000010',
         v_farmer2, v_prem_farmer, 'seed-pago-001', 19.90, 'PEN', 'APPROVED',
         v_now - INTERVAL '3 days', v_now - INTERVAL '3 days'),
        ('90000000-0000-4000-8000-000000000002', 'c0000000-0000-4000-8000-000000000011',
         v_vet2, v_prem_vet, 'seed-pago-002', 39.90, 'PEN', 'APPROVED',
         v_now - INTERVAL '7 days', v_now - INTERVAL '7 days'),
        -- Intento fallido del ganadero 1: sigue en el plan gratuito
        ('90000000-0000-4000-8000-000000000003', 'c0000000-0000-4000-8000-000000000001',
         v_farmer, v_prem_farmer, 'seed-pago-003', 19.90, 'PEN', 'DECLINED',
         v_now - INTERVAL '1 day', v_now - INTERVAL '1 day')
    ON CONFLICT (id) DO NOTHING;

    -- ====================================================== 4. capacidades
    -- Los contadores "activos" se recalculan al final del script.
    INSERT INTO livestock_inventory_capacity
        (owner_id, allowed_animals, active_animals, last_plan_revision, updated_at)
    VALUES
        (v_farmer,    15, 3, 1, v_now),   -- V9: se recalcula a 8
        (v_farmer2,  150, 4, 2, v_now - INTERVAL '3 days'),
        (v_farmer3,   15, 2, 1, v_now - INTERVAL '40 days'),
        (v_suspended, 15, 0, 1, v_now - INTERVAL '3 days')
    ON CONFLICT (owner_id) DO NOTHING;

    INSERT INTO linking_capacity (vet_id, allowed_ranchers, active_links, last_plan_revision, updated_at)
    VALUES
        (v_vet,  3, 1, 1, v_now),        -- V9: se recalcula a 2
        (v_vet2, 25, 1, 2, v_now - INTERVAL '7 days')
    ON CONFLICT (vet_id) DO NOTHING;

    -- =========================================================== 5. fincas
    INSERT INTO livestock_farms (id, farmer_id, name, location, created_at, updated_at)
    VALUES
        (v_farm,  v_farmer,  'Fundo Demo',        'Chiclayo, Lambayeque',   v_now, v_now),
        (v_farm2, v_farmer,  'Los Álamos',        'Pimentel, Lambayeque',   v_now - INTERVAL '120 days', v_now - INTERVAL '30 days'),
        (v_farm3, v_farmer2, 'Granja San José',   'Chiclayo, Lambayeque',   v_now - INTERVAL '30 days',  v_now - INTERVAL '30 days'),
        (v_farm4, v_farmer3, 'Estancia La Vega',  'Ferreñafe, Lambayeque',  v_now - INTERVAL '40 days',  v_now - INTERVAL '40 days')
    ON CONFLICT (id) DO NOTHING;

    -- ========================================================= 6. animales
    -- Finca 1 - Fundo Demo (existente: BOV-001, BOV-002, OV-001)
    INSERT INTO livestock_animals
        (id, farm_id, owner_id, species_id, code, name, breed, sex, birth_date, status, created_at, updated_at)
    VALUES
        ('e0000000-0000-4000-8000-000000000001', v_farm, v_farmer, v_bovino, 'BOV-001', 'Luna', 'Holstein', 'HEMBRA', '2021-03-12', 'ACTIVO', v_now, v_now),
        ('e0000000-0000-4000-8000-000000000002', v_farm, v_farmer, v_bovino, 'BOV-002', 'Rayo', 'Brahman', 'MACHO', '2022-01-20', 'ACTIVO', v_now, v_now),
        ('e0000000-0000-4000-8000-000000000003', v_farm, v_farmer, v_ovino, 'OV-001', 'Mora', 'Corriedale', 'HEMBRA', '2021-09-05', 'ACTIVO', v_now, v_now)
    ON CONFLICT (id) DO NOTHING;

    -- Finca 1 - Fundo Demo (nuevos)
    INSERT INTO livestock_animals
        (id, farm_id, owner_id, species_id, code, name, breed, sex, birth_date, status, created_at, updated_at)
    VALUES
        ('e0000000-0000-4000-8000-000000000011', v_farm, v_farmer, v_bovino,  'BOV-003', 'Trueno',     'Angus',           'MACHO',  '2020-05-18', 'ACTIVO', v_now - INTERVAL '60 days', v_now - INTERVAL '15 days'),
        ('e0000000-0000-4000-8000-000000000012', v_farm, v_farmer, v_caprino, 'CAP-001', 'Nube',       'Alpina',          'HEMBRA', '2022-07-03', 'ACTIVO', v_now - INTERVAL '45 days', v_now - INTERVAL '5 days'),
        ('e0000000-0000-4000-8000-000000000013', v_farm, v_farmer, v_equino,  'EQU-001', 'Relámpago',  'Criollo',         'MACHO',  '2019-11-25', 'ACTIVO', v_now - INTERVAL '90 days', v_now - INTERVAL '90 days')
    ON CONFLICT (id) DO NOTHING;

    -- Finca 2 - Los Álamos (mismo ganadero; OV-002 está VENDIDO)
    INSERT INTO livestock_animals
        (id, farm_id, owner_id, species_id, code, name, breed, sex, birth_date, status, created_at, updated_at)
    VALUES
        ('e0000000-0000-4000-8000-000000000014', v_farm2, v_farmer, v_bovino,  'BOV-004', 'Estrella', 'Holstein',      'HEMBRA', '2021-06-10', 'ACTIVO',   v_now - INTERVAL '120 days', v_now - INTERVAL '120 days'),
        ('e0000000-0000-4000-8000-000000000015', v_farm2, v_farmer, v_porcino, 'POR-001', 'Tocino',   'Landrace',      'MACHO',  '2023-02-14', 'ACTIVO',   v_now - INTERVAL '110 days', v_now - INTERVAL '110 days'),
        ('e0000000-0000-4000-8000-000000000016', v_farm2, v_farmer, v_ovino,   'OV-002',  'Lana',     'Merino',        'HEMBRA', '2020-12-01', 'VENDIDO',  v_now - INTERVAL '120 days', v_now - INTERVAL '10 days')
    ON CONFLICT (id) DO NOTHING;

    -- Finca 3 - Granja San José (ganadero 2, premium)
    INSERT INTO livestock_animals
        (id, farm_id, owner_id, species_id, code, name, breed, sex, birth_date, status, created_at, updated_at)
    VALUES
        ('e0000000-0000-4000-8000-000000000017', v_farm3, v_farmer2, v_bovino,  'BOV-101', 'Canela', 'Gyr',          'HEMBRA', '2022-04-22', 'ACTIVO', v_now - INTERVAL '30 days', v_now - INTERVAL '6 days'),
        ('e0000000-0000-4000-8000-000000000018', v_farm3, v_farmer2, v_bovino,  'BOV-102', 'Bruno',  'Brahman',      'MACHO',  '2021-08-30', 'ACTIVO', v_now - INTERVAL '30 days', v_now - INTERVAL '8 days'),
        ('e0000000-0000-4000-8000-000000000019', v_farm3, v_farmer2, v_porcino, 'POR-002', 'Pelusa', 'Large White',  'HEMBRA', '2023-05-19', 'ACTIVO', v_now - INTERVAL '30 days', v_now - INTERVAL '30 days'),
        ('e0000000-0000-4000-8000-000000000020', v_farm3, v_farmer2, v_caprino, 'CAP-002', 'Ámbar',  'Saanen',       'HEMBRA', '2022-11-11', 'ACTIVO', v_now - INTERVAL '30 days', v_now - INTERVAL '30 days')
    ON CONFLICT (id) DO NOTHING;

    -- Finca 4 - Estancia La Vega (ganadero 3)
    INSERT INTO livestock_animals
        (id, farm_id, owner_id, species_id, code, name, breed, sex, birth_date, status, created_at, updated_at)
    VALUES
        ('e0000000-0000-4000-8000-000000000021', v_farm4, v_farmer3, v_ovino,  'OV-101',  'Turbina', 'Corriedale',       'MACHO',  '2022-02-08', 'ACTIVO', v_now - INTERVAL '40 days', v_now - INTERVAL '4 days'),
        ('e0000000-0000-4000-8000-000000000022', v_farm4, v_farmer3, v_equino, 'EQU-101', 'Sombra',  'Peruano de Paso',  'HEMBRA', '2018-03-15', 'ACTIVO', v_now - INTERVAL '40 days', v_now - INTERVAL '40 days')
    ON CONFLICT (id) DO NOTHING;

    -- ==================================================== 7. observaciones
    INSERT INTO livestock_observations (id, animal_id, author_id, text, created_at)
    VALUES ('e0000000-0000-4000-8000-000000000010',
            'e0000000-0000-4000-8000-000000000001', v_farmer,
            'Se adapta bien al lote y mantiene buen apetito.', v_now)
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO livestock_observations (id, animal_id, author_id, text, created_at)
    VALUES
        ('e0000000-0000-4000-8000-000000000031', 'e0000000-0000-4000-8000-000000000002', v_farmer,
         'Buen estado corporal; se controló el peso de rutina.', v_now - INTERVAL '20 days'),
        ('e0000000-0000-4000-8000-000000000032', 'e0000000-0000-4000-8000-000000000011', v_farmer,
         'Se incorporó al lote sin problemas tras la feria.', v_now - INTERVAL '15 days'),
        ('e0000000-0000-4000-8000-000000000033', 'e0000000-0000-4000-8000-000000000016', v_farmer,
         'Vendida a productor de Ferreñafe; se retira del inventario.', v_now - INTERVAL '10 days'),
        ('e0000000-0000-4000-8000-000000000034', 'e0000000-0000-4000-8000-000000000012', v_farmer,
         'Revisión de pezuñas pendiente para la próxima visita.', v_now - INTERVAL '5 days'),
        ('e0000000-0000-4000-8000-000000000035', 'e0000000-0000-4000-8000-000000000017', v_farmer2,
         'Leve cojera anterior, hoy sin signos.', v_now - INTERVAL '6 days'),
        ('e0000000-0000-4000-8000-000000000036', 'e0000000-0000-4000-8000-000000000018', v_farmer2,
         'Buen estado corporal, listo para servicio.', v_now - INTERVAL '8 days'),
        ('e0000000-0000-4000-8000-000000000037', 'e0000000-0000-4000-8000-000000000021', v_farmer3,
         'Pesaje realizado; dentro del rango esperado.', v_now - INTERVAL '4 days')
    ON CONFLICT (id) DO NOTHING;

    -- ================================== 8. invitaciones y vínculos veterinarios
    INSERT INTO linking_invitations
        (id, farmer_id, vet_email, vet_id, status, email_delivery, sent_at, expires_at, responded_at)
    VALUES
        -- ACEPTADA: ganadero 1 con el vet 1 (origen del vínculo principal)
        ('f0000000-0000-4000-8000-000000000001', v_farmer, 'demo.veterinario@anitec.pe',
         v_vet, 'ACEPTADA', 'SENT', v_now, v_now + INTERVAL '7 days', v_now),
        -- PENDIENTE: en la bandeja del segundo veterinario (vence en 6 días)
        ('f0000000-0000-4000-8000-000000000011', v_farmer, 'demo.veterinario2@anitec.pe',
         v_vet2, 'PENDIENTE', 'SENT', v_now - INTERVAL '1 day', v_now + INTERVAL '6 days', NULL),
        -- PENDIENTE: reintento del ganadero 2 tras la respuesta de hace 4 días
        -- (en la bandeja del primer veterinario, que acepta hasta 3 vínculos)
        ('f0000000-0000-4000-8000-000000000017', v_farmer2, 'demo.veterinario@anitec.pe',
         v_vet, 'PENDIENTE', 'SENT', v_now - INTERVAL '1 day', v_now + INTERVAL '6 days', NULL),
        -- RECHAZADA: el vet 1 rechazó una segunda invitación tras revocar el vínculo
        ('f0000000-0000-4000-8000-000000000012', v_farmer2, 'demo.veterinario@anitec.pe',
         v_vet, 'RECHAZADA', 'SENT', v_now - INTERVAL '5 days', v_now + INTERVAL '2 days',
         v_now - INTERVAL '4 days'),
        -- VENCIDA: expiró sin respuesta (estado creado en la migración V10)
        ('f0000000-0000-4000-8000-000000000013', v_farmer3, 'demo.veterinario2@anitec.pe',
         v_vet2, 'VENCIDA', 'SENT', v_now - INTERVAL '9 days', v_now - INTERVAL '2 days', NULL),
        -- ACEPTADA: ganadero 3 con el vet 1 (segundo vínculo del vet 1)
        ('f0000000-0000-4000-8000-000000000014', v_farmer3, 'demo.veterinario@anitec.pe',
         v_vet, 'ACEPTADA', 'SENT', v_now - INTERVAL '8 days', v_now - INTERVAL '1 day',
         v_now - INTERVAL '7 days'),
        -- ACEPTADA: ganadero 2 con el vet 2
        ('f0000000-0000-4000-8000-000000000015', v_farmer2, 'demo.veterinario2@anitec.pe',
         v_vet2, 'ACEPTADA', 'SENT', v_now - INTERVAL '6 days', v_now + INTERVAL '1 day',
         v_now - INTERVAL '6 days'),
        -- ACEPTADA (histórica): ganadero 2 con el vet 1, luego revocada
        ('f0000000-0000-4000-8000-000000000016', v_farmer2, 'demo.veterinario@anitec.pe',
         v_vet, 'ACEPTADA', 'SENT', v_now - INTERVAL '20 days', v_now - INTERVAL '13 days',
         v_now - INTERVAL '19 days')
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO linking_links (id, invitation_id, farmer_id, vet_id, status, linked_at)
    VALUES
        ('f0000000-0000-4000-8000-000000000002', 'f0000000-0000-4000-8000-000000000001',
         v_farmer, v_vet, 'ACTIVA', v_now),
        ('f0000000-0000-4000-8000-000000000021', 'f0000000-0000-4000-8000-000000000014',
         v_farmer3, v_vet, 'ACTIVA', v_now - INTERVAL '7 days'),
        ('f0000000-0000-4000-8000-000000000022', 'f0000000-0000-4000-8000-000000000015',
         v_farmer2, v_vet2, 'ACTIVA', v_now - INTERVAL '6 days'),
        -- Vinculo revocado (el ganadero 2 se volvió a vincular con el vet 2)
        ('f0000000-0000-4000-8000-000000000023', 'f0000000-0000-4000-8000-000000000016',
         v_farmer2, v_vet, 'REVOCADA', v_now - INTERVAL '19 days')
    ON CONFLICT (id) DO NOTHING;

    -- Actualiza revoked_at del vinculo revocado (sin tocar los activos)
    UPDATE linking_links
       SET revoked_at = v_now - INTERVAL '10 days'
     WHERE id = 'f0000000-0000-4000-8000-000000000023'
       AND revoked_at IS NULL;

    -- ================================================== 9. citas veterinarias
    -- 9a) Citas FUTURAS (CHECK: scheduled_at > CURRENT_TIMESTAMP)
    INSERT INTO care_appointments
        (id, animal_id, owner_id, veterinarian_id, type, scheduled_at, reason, status, origin_attention_id, created_at)
    VALUES
        -- Visita de control general del ganadero 1 con el vet 1
        ('f0000000-0000-4000-8000-000000000003', 'e0000000-0000-4000-8000-000000000001',
         v_farmer, v_vet, 'VISITA', v_now + INTERVAL '2 days', 'Control general',
         'PROGRAMADA', NULL, v_now),
        -- Control de seguimiento nacido de una atención (US12)
        ('f0000000-0000-4000-8000-000000000031', 'e0000000-0000-4000-8000-000000000002',
         v_farmer, v_vet, 'CONTROL', v_now + INTERVAL '1 day', 'Control de peso posvacunación',
         'PROGRAMADA', 'f0000000-0000-4000-8000-000000000042', v_now - INTERVAL '10 minutes'),
        ('f0000000-0000-4000-8000-000000000032', 'e0000000-0000-4000-8000-000000000021',
         v_farmer3, v_vet, 'VISITA', v_now + INTERVAL '3 days', 'Vacunación de rutina',
         'PROGRAMADA', NULL, v_now - INTERVAL '1 day'),
        ('f0000000-0000-4000-8000-000000000033', 'e0000000-0000-4000-8000-000000000017',
         v_farmer2, v_vet2, 'CONTROL', v_now + INTERVAL '2 days', 'Control reproductivo',
         'PROGRAMADA', NULL, v_now - INTERVAL '6 hours'),
        -- Cancelada con antelación
        ('f0000000-0000-4000-8000-000000000034', 'e0000000-0000-4000-8000-000000000012',
         v_farmer, v_vet, 'VISITA', v_now + INTERVAL '4 days', 'Revisión de pezuñas',
         'CANCELADA', NULL, v_now - INTERVAL '2 days')
    ON CONFLICT (id) DO NOTHING;

    -- 9b) Citas PASADAS.
    --     El CHECK (scheduled_at > CURRENT_TIMESTAMP) impide INSERTar en el pasado
    --     y también retroceder la fecha (el UPDATE re-evalúa el CHECK), pero NO se
    --     re-evalúa al modificar únicamente "status". Por eso se insertan con unos
    --     segundos de adelanto, se espera y se marcan COMPLETADA / CANCELADA.
    INSERT INTO care_appointments
        (id, animal_id, owner_id, veterinarian_id, type, scheduled_at, reason, status, created_at)
    VALUES
        ('f0000000-0000-4000-8000-000000000035', 'e0000000-0000-4000-8000-000000000011',
         v_farmer, v_vet, 'VISITA', v_now + INTERVAL '5 seconds', 'Vacunación de rutina',
         'PROGRAMADA', v_now - INTERVAL '5 days'),
        ('f0000000-0000-4000-8000-000000000036', 'e0000000-0000-4000-8000-000000000018',
         v_farmer2, v_vet2, 'VISITA', v_now + INTERVAL '5 seconds', 'Control general',
         'PROGRAMADA', v_now - INTERVAL '8 days'),
        ('f0000000-0000-4000-8000-000000000037', 'e0000000-0000-4000-8000-000000000022',
         v_farmer3, v_vet, 'VISITA', v_now + INTERVAL '5 seconds', 'Traslado de equino',
         'PROGRAMADA', v_now - INTERVAL '3 days')
    ON CONFLICT (id) DO NOTHING;

    -- Solo la primera vez: esperar a que las citas recién insertadas queden en el
    -- pasado y marcarlas. En re-ejecuciones ya están resueltas y se omite la espera.
    IF EXISTS (SELECT 1 FROM care_appointments
                WHERE id IN ('f0000000-0000-4000-8000-000000000035',
                             'f0000000-0000-4000-8000-000000000036',
                             'f0000000-0000-4000-8000-000000000037')
                  AND status = 'PROGRAMADA') THEN
        PERFORM pg_sleep(6);  -- las citas de arriba pasan a ser "vencidas"

        UPDATE care_appointments SET status = 'COMPLETADA'
         WHERE id IN ('f0000000-0000-4000-8000-000000000035',
                      'f0000000-0000-4000-8000-000000000036')
           AND status = 'PROGRAMADA';

        UPDATE care_appointments SET status = 'CANCELADA'
         WHERE id = 'f0000000-0000-4000-8000-000000000037'
           AND status = 'PROGRAMADA';
    END IF;

    -- ================================================ 10. historias clínicas
    INSERT INTO care_records
        (id, animal_id, owner_id, veterinarian_id, appointment_id, title, description, attention_date, created_at)
    VALUES
        -- Atención de hoy del ganadero 1 (Luna)
        ('f0000000-0000-4000-8000-000000000004', 'e0000000-0000-4000-8000-000000000001',
         v_farmer, v_vet, NULL, 'Control general',
         'Se realizó revisión preventiva completa y control de peso.',
         CURRENT_DATE, v_now),
        -- Atención de HOY ligada a la visita COMPLETADA recién finalizada
        ('f0000000-0000-4000-8000-000000000041', 'e0000000-0000-4000-8000-000000000011',
         v_farmer, v_vet, 'f0000000-0000-4000-8000-000000000035', 'Vacunación y control general',
         'Se aplicó vacuna y se controló peso corporal. Sin reacciones adversas.',
         CURRENT_DATE, v_now),
        ('f0000000-0000-4000-8000-000000000042', 'e0000000-0000-4000-8000-000000000002',
         v_farmer, v_vet, NULL, 'Neumonía leve',
         'Cuadro respiratorio leve; respondió favorablemente al tratamiento antibiótico.',
         (CURRENT_DATE - 6), v_now - INTERVAL '6 days'),
        -- Atención de HOY ligada a la visita COMPLETADA recién finalizada
        ('f0000000-0000-4000-8000-000000000043', 'e0000000-0000-4000-8000-000000000018',
         v_farmer2, v_vet2, 'f0000000-0000-4000-8000-000000000036', 'Control general',
         'Revisión clínica completa; estado corporal adecuado.',
         CURRENT_DATE, v_now),
        ('f0000000-0000-4000-8000-000000000044', 'e0000000-0000-4000-8000-000000000017',
         v_farmer2, v_vet2, NULL, 'Control reproductivo',
         'Evaluación de ciclo reproductivo; se recomienda repetir en 30 días.',
         (CURRENT_DATE - 5), v_now - INTERVAL '5 days'),
        ('f0000000-0000-4000-8000-000000000045', 'e0000000-0000-4000-8000-000000000021',
         v_farmer3, v_vet, NULL, 'Desparasitación',
         'Desparasitación de rutina según peso vivo.',
         (CURRENT_DATE - 3), v_now - INTERVAL '3 days')
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO care_treatments (id, care_record_id, description, applied_date, created_at)
    VALUES
        ('f0000000-0000-4000-8000-000000000005', 'f0000000-0000-4000-8000-000000000004',
         'Vitamina B12 inyectable 5ml', CURRENT_DATE, v_now),
        ('f0000000-0000-4000-8000-000000000051', 'f0000000-0000-4000-8000-000000000042',
         'Amoxicilina 5 ml intramuscular, 1 dosis diaria por 5 días',
         (CURRENT_DATE - 6), v_now - INTERVAL '6 days'),
        ('f0000000-0000-4000-8000-000000000052', 'f0000000-0000-4000-8000-000000000043',
         'Ibuprofeno 4 ml subcutáneo, dosis única',
         CURRENT_DATE, v_now),
        ('f0000000-0000-4000-8000-000000000053', 'f0000000-0000-4000-8000-000000000044',
         'Ocitocina 2 ml intramuscular',
         (CURRENT_DATE - 5), v_now - INTERVAL '5 days'),
        ('f0000000-0000-4000-8000-000000000054', 'f0000000-0000-4000-8000-000000000045',
         'Ivermectina 6 ml según peso vivo',
         (CURRENT_DATE - 3), v_now - INTERVAL '3 days')
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO care_vaccinations (id, care_record_id, name, applied_date, created_at)
    VALUES
        ('f0000000-0000-4000-8000-000000000006', 'f0000000-0000-4000-8000-000000000004',
         'Aftosa', CURRENT_DATE, v_now),
        ('f0000000-0000-4000-8000-000000000061', 'f0000000-0000-4000-8000-000000000041',
         'Brucelosis', CURRENT_DATE, v_now),
        ('f0000000-0000-4000-8000-000000000062', 'f0000000-0000-4000-8000-000000000042',
         'Carbón', (CURRENT_DATE - 6), v_now - INTERVAL '6 days'),
        ('f0000000-0000-4000-8000-000000000063', 'f0000000-0000-4000-8000-000000000044',
         'Brucelosis', (CURRENT_DATE - 5), v_now - INTERVAL '5 days'),
        ('f0000000-0000-4000-8000-000000000064', 'f0000000-0000-4000-8000-000000000045',
         'Clostridiosis', (CURRENT_DATE - 3), v_now - INTERVAL '3 days')
    ON CONFLICT (id) DO NOTHING;

    -- Instrucciones: la atención ...0042 tiene DOS versiones (US20)
    INSERT INTO care_instruction_versions (id, care_record_id, content, author_id, created_at)
    VALUES
        ('f0000000-0000-4000-8000-000000000007', 'f0000000-0000-4000-8000-000000000004',
         'Mantener en aislamiento por 48 horas e hidratación constante.', v_vet, v_now),
        ('f0000000-0000-4000-8000-000000000071', 'f0000000-0000-4000-8000-000000000042',
         'Aislamiento por 48 horas, hidratación constante y temperatura corporal dos veces al día.',
         v_vet, v_now - INTERVAL '6 days'),
        ('f0000000-0000-4000-8000-000000000072', 'f0000000-0000-4000-8000-000000000042',
         'Ampliar el aislamiento a 72 horas; repetir la nebulización y mantener la alimentación separada.',
         v_vet, v_now - INTERVAL '5 days'),
        ('f0000000-0000-4000-8000-000000000073', 'f0000000-0000-4000-8000-000000000043',
         'Mantener en lote de descanso 48 horas y ofrecer forraje de buena calidad.',
         v_vet2, v_now)
    ON CONFLICT (id) DO NOTHING;

    -- ============================================== 11. notificaciones in-app
    -- (tipos y textos idénticos a NotificationService)
    INSERT INTO notification_notifications (id, user_id, type, title, message, is_read, created_at)
    VALUES
        ('60000000-0000-4000-8000-000000000001', v_vet2, 'INVITATION_RECEIVED',
         'Invitación de vinculación recibida',
         'Un ganadero le ha invitado a vincularse. Revise la invitación pendiente.',
         FALSE, v_now - INTERVAL '1 day'),
        ('60000000-0000-4000-8000-000000000002', v_farmer, 'INVITATION_ANSWERED',
         'Invitación aceptada', 'El veterinario aceptó la invitación de vinculación.',
         TRUE, v_now),
        ('60000000-0000-4000-8000-000000000003', v_farmer, 'VISIT_SCHEDULED',
         'Visita veterinaria programada',
         'Se programó una visita para uno de sus animales. Consulte su agenda en la aplicación.',
         FALSE, v_now - INTERVAL '2 days'),
        ('60000000-0000-4000-8000-000000000004', v_farmer2, 'PREMIUM_ACTIVATED',
         'Suscripción premium activa', 'Su plan premium está activo. Límite vigente: 150.',
         TRUE, v_now - INTERVAL '3 days'),
        ('60000000-0000-4000-8000-000000000005', v_vet2, 'PREMIUM_ACTIVATED',
         'Suscripción premium activa', 'Su plan premium está activo. Límite vigente: 25.',
         TRUE, v_now - INTERVAL '7 days'),
        ('60000000-0000-4000-8000-000000000006', v_farmer, 'CARE_INSTRUCTIONS_REGISTERED',
         'Nuevas indicaciones de cuidado',
         'El veterinario registró indicaciones de cuidado para uno de sus animales. Inicie sesión para consultarlas.',
         FALSE, v_now - INTERVAL '6 days'),
        ('60000000-0000-4000-8000-000000000007', v_farmer, 'CARE_INSTRUCTIONS_UPDATED',
         'Indicaciones de cuidado actualizadas',
         'El veterinario actualizó las indicaciones de cuidado de una atención. Inicie sesión para consultarlas.',
         TRUE, v_now - INTERVAL '5 days'),
        ('60000000-0000-4000-8000-000000000008', v_farmer3, 'VISIT_SCHEDULED',
         'Visita veterinaria programada',
         'Se programó una visita para uno de sus animales. Consulte su agenda en la aplicación.',
         FALSE, v_now - INTERVAL '1 day'),
        ('60000000-0000-4000-8000-000000000009', v_farmer2, 'INVITATION_ANSWERED',
         'Invitación rechazada', 'El veterinario rechazó la invitación de vinculación.',
         TRUE, v_now - INTERVAL '4 days'),
        ('60000000-0000-4000-8000-000000000010', v_vet, 'INVITATION_RECEIVED',
         'Invitación de vinculación recibida',
         'Un ganadero le ha invitado a vincularse. Revise la invitación pendiente.',
         FALSE, v_now - INTERVAL '1 day'),
        ('60000000-0000-4000-8000-000000000011', v_vet, 'INVITATION_RECEIVED',
         'Invitación de vinculación recibida',
         'Un ganadero le ha invitado a vincularse. Revise la invitación pendiente.',
         TRUE, v_now - INTERVAL '8 days')
    ON CONFLICT (id) DO NOTHING;

    -- ================================================= 12. auditoría de admin
    -- (acciones idénticas a las que registra AdminService)
    INSERT INTO admin_audit_log (id, actor_id, action, entity_type, entity_id, details, created_at)
    VALUES
        ('70000000-0000-4000-8000-000000000001', 'd0000000-0000-4000-8000-0000000000ff',
         'USER_STATUS_CHANGED', 'Account', v_suspended, 'status=SUSPENDIDA', v_now - INTERVAL '3 days'),
        ('70000000-0000-4000-8000-000000000002', 'd0000000-0000-4000-8000-0000000000ff',
         'USER_STATUS_CHANGED', 'Account', v_vet2, 'status=ACTIVA', v_now - INTERVAL '7 days'),
        ('70000000-0000-4000-8000-000000000003', 'd0000000-0000-4000-8000-0000000000ff',
         'SPECIES_CREATED', 'Species', v_equino, 'name=Equino', v_now - INTERVAL '10 days'),
        ('70000000-0000-4000-8000-000000000004', 'd0000000-0000-4000-8000-0000000000ff',
         'SPECIES_CREATED', 'Species', v_porcino, 'name=Porcino', v_now - INTERVAL '10 days'),
        ('70000000-0000-4000-8000-000000000005', 'd0000000-0000-4000-8000-0000000000ff',
         'SPECIES_UPDATED', 'Species', v_bovino, 'name=Bovino', v_now - INTERVAL '8 days'),
        ('70000000-0000-4000-8000-000000000006', 'd0000000-0000-4000-8000-0000000000ff',
         'PLAN_UPDATED', 'Plan', v_prem_farmer, 'capacity=150', v_now - INTERVAL '7 days'),
        ('70000000-0000-4000-8000-000000000007', 'd0000000-0000-4000-8000-0000000000ff',
         'PLAN_UPDATED', 'Plan', v_prem_vet, 'capacity=25', v_now - INTERVAL '7 days')
    ON CONFLICT (id) DO NOTHING;
END $$;

-- ============================================== 13. recálculo de contadores
-- Garantiza que los contadores de capacidad coincidan con las filas reales,
-- tanto en una base recién creada como en una donde ya se hubiese corrido una
-- versión anterior de este script.
UPDATE livestock_inventory_capacity c
   SET active_animals = (SELECT count(*) FROM livestock_animals a
                          WHERE a.owner_id = c.owner_id AND a.status = 'ACTIVO'),
       updated_at = CURRENT_TIMESTAMP
 WHERE c.owner_id IN ('d0000000-0000-4000-8000-000000000001',
                      'd0000000-0000-4000-8000-000000000010',
                      'd0000000-0000-4000-8000-000000000012',
                      'd0000000-0000-4000-8000-000000000013');

UPDATE linking_capacity c
   SET active_links = (SELECT count(*) FROM linking_links l
                        WHERE l.vet_id = c.vet_id AND l.status = 'ACTIVA'),
       updated_at = CURRENT_TIMESTAMP
 WHERE c.vet_id IN ('d0000000-0000-4000-8000-000000000002',
                    'd0000000-0000-4000-8000-000000000011');

-- ============================================================== 14. resumen
-- 1) Cuentas demo (deben salir 6: 5 ACTIVA + 1 SUSPENDIDA, todas verificadas)
SELECT email, role, status, verified_at IS NOT NULL AS verificada
  FROM identity_accounts
 WHERE email LIKE 'demo.%'
 ORDER BY role, email;

-- 2) Planes: gratuito vs premium (ends_at es el vencimiento del período pago)
SELECT a.email, s.plan_type, s.status, s.allowed_capacity AS limite,
       s.revision, s.ends_at::date AS vence
  FROM subscriptions_subscriptions s
  JOIN identity_accounts a ON a.id = s.account_id
 WHERE a.email LIKE 'demo.%'
 ORDER BY s.plan_type, a.email;

-- 3) Contadores contra las filas reales (deben coincidir)
SELECT a.email,
       (SELECT count(*) FROM livestock_animals an
         WHERE an.owner_id = c.owner_id AND an.status = 'ACTIVO') AS activos,
       c.allowed_animals AS limite, c.last_plan_revision AS revision
  FROM livestock_inventory_capacity c
  JOIN identity_accounts a ON a.id = c.owner_id
 WHERE a.email LIKE 'demo.%'
 ORDER BY a.email;

SELECT a.email,
       (SELECT count(*) FROM linking_links l
         WHERE l.vet_id = c.vet_id AND l.status = 'ACTIVA') AS vinculos,
       c.allowed_ranchers AS limite, c.last_plan_revision AS revision
  FROM linking_capacity c
  JOIN identity_accounts a ON a.id = c.vet_id
 WHERE a.email LIKE 'demo.%'
 ORDER BY a.email;

-- 4) Invitaciones por estado (debe aparecer PENDIENTE, ACEPTADA, RECHAZADA y VENCIDA)
SELECT status, count(*) AS total FROM linking_invitations GROUP BY status ORDER BY status;

-- 5) Citas: PROGRAMADA siempre futura; COMPLETADA/CANCELADA incluyen fechas ya vencidas
SELECT status, count(*) AS total,
       min(scheduled_at)::date AS mas_antigua,
       max(scheduled_at)::date AS mas_reciente
  FROM care_appointments GROUP BY status ORDER BY status;
