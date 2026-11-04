package com.anitec.backend;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
class RemoveAdminMigrationTests {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container
    static PostgreSQLContainer<?> upgradePostgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private PostgreSQLContainer<?> database(String scenario) {
        return scenario.equals("fresh") ? postgres : upgradePostgres;
    }

    private Flyway flyway(String schema, String target, boolean demo) {
        var db = database(schema);
        return Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
                .target(target)
                .placeholders(Map.of("admin_email", "retired-admin@bootstrap.invalid",
                        "admin_password_hash", "DISABLED-HISTORICAL-BOOTSTRAP", "demo_seed", Boolean.toString(demo)))
                .load();
    }

    private JdbcTemplate jdbc(String schema) {
        var db = database(schema);
        return new JdbcTemplate(new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()));
    }

    private void assertFinalSchema(String schema, JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_accounts WHERE role='ADMIN'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT to_regclass('public.admin_audit_log')", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM livestock_species", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscriptions_plans", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(14);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO identity_accounts(email,password_hash,name,last_name,role)
                VALUES ('forbidden@example.invalid','disabled','Test','Probe','ADMIN')
                """)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void freshDatabaseMigratesThroughLatestWithoutPrivilegedAccounts() {
        flyway("fresh", "latest", false).migrate();
        assertFinalSchema("fresh", jdbc("fresh"));
        assertThat(jdbc("fresh").queryForObject("SELECT count(*) FROM identity_accounts", Integer.class)).isZero();
        assertThat(flyway("fresh", "latest", false).migrate().migrationsExecuted).isZero();
    }

    @Test
    void upgradesV10AndCleansCrossContextReferencesWhilePreservingProductData() {
        flyway("upgrade", "10", true).migrate();
        var jdbc = jdbc("upgrade");
        UUID admin = jdbc.queryForObject("SELECT id FROM identity_accounts WHERE role='ADMIN'", UUID.class);
        UUID farmer = jdbc.queryForObject("SELECT id FROM identity_accounts WHERE role='GANADERO'", UUID.class);
        UUID vet = jdbc.queryForObject("SELECT id FROM identity_accounts WHERE role='VETERINARIO'", UUID.class);
        UUID animal = jdbc.queryForObject("SELECT id FROM livestock_animals ORDER BY code LIMIT 1", UUID.class);
        jdbc.update("INSERT INTO identity_refresh_tokens(account_id,token_digest,expires_at) VALUES (?, ?, now()+interval '1 day')", admin, "x".repeat(64));
        jdbc.update("INSERT INTO notification_notifications(user_id,type,title,message) VALUES (?,'TEST','Probe','Probe')", admin);
        jdbc.update("INSERT INTO livestock_inventory_capacity(owner_id,allowed_animals) VALUES (?,15)", admin);
        UUID farm = jdbc.queryForObject("INSERT INTO livestock_farms(farmer_id,name) VALUES (?,'Retired') RETURNING id", UUID.class, admin);
        jdbc.update("INSERT INTO livestock_animals(farm_id,owner_id,species_id,code,sex) SELECT ?,?,id,'RETIRED','HEMBRA' FROM livestock_species LIMIT 1", farm, admin);
        jdbc.update("INSERT INTO livestock_observations(animal_id,author_id,text) VALUES (?,?,'Retired')", animal, admin);
        UUID invitation = jdbc.queryForObject("INSERT INTO linking_invitations(farmer_id,vet_id,vet_email,expires_at) VALUES (?,?, 'retired@example.invalid',now()+interval '7 days') RETURNING id", UUID.class, admin, vet);
        jdbc.update("INSERT INTO linking_links(invitation_id,farmer_id,vet_id) VALUES (?,?,?)", invitation, admin, vet);
        jdbc.update("UPDATE linking_capacity SET active_links=2 WHERE vet_id=?", vet);
        jdbc.update("INSERT INTO linking_capacity(vet_id,allowed_ranchers) VALUES (?,3)", admin);
        UUID appointment = jdbc.queryForObject("INSERT INTO care_appointments(animal_id,owner_id,veterinarian_id,type,scheduled_at) VALUES (?,?,?,'VISITA',now()+interval '1 day') RETURNING id", UUID.class, animal, farmer, admin);
        UUID record = jdbc.queryForObject("INSERT INTO care_records(animal_id,owner_id,veterinarian_id,appointment_id,title,attention_date) VALUES (?,?,?,?,'Retired',CURRENT_DATE) RETURNING id", UUID.class, animal, farmer, admin, appointment);
        jdbc.update("INSERT INTO care_treatments(care_record_id,description,applied_date) VALUES (?,'Retired',CURRENT_DATE)", record);
        jdbc.update("INSERT INTO care_vaccinations(care_record_id,name,applied_date) VALUES (?,'Retired',CURRENT_DATE)", record);
        jdbc.update("INSERT INTO care_instruction_versions(care_record_id,content,author_id) VALUES (?,'Retired',?)", record, admin);
        UUID preservedRecord = jdbc.queryForObject("INSERT INTO care_records(animal_id,owner_id,veterinarian_id,appointment_id,title,attention_date) VALUES (?,?,?,?,'Keep history',CURRENT_DATE) RETURNING id", UUID.class, animal, farmer, vet, appointment);
        jdbc.update("INSERT INTO care_appointments(animal_id,owner_id,veterinarian_id,type,scheduled_at,origin_attention_id) VALUES (?,?,?,'CONTROL',now()+interval '2 days',?)", animal, farmer, vet, record);
        UUID subscription = jdbc.queryForObject("INSERT INTO subscriptions_subscriptions(account_id,profile,plan_id,plan_type,allowed_capacity) SELECT ?,'GANADERO',id,'GRATUITO',15 FROM subscriptions_plans WHERE profile='GANADERO' AND plan_type='GRATUITO' RETURNING id", UUID.class, admin);
        jdbc.update("INSERT INTO subscriptions_payments(subscription_id,account_id,plan_id,gateway_ref,amount,status) SELECT ?,?,plan_id,'retired-payment',0,'PENDING' FROM subscriptions_subscriptions WHERE id=?", subscription, admin, subscription);
        // Upgrading V10 now applies V11-V14, preserving product records.
        assertThat(flyway("upgrade", "latest", true).migrate().migrationsExecuted).isEqualTo(4);
        assertFinalSchema("upgrade", jdbc);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_accounts", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM livestock_animals", Integer.class)).isEqualTo(3);
        for (String query : new String[]{
                "SELECT count(*) FROM identity_refresh_tokens WHERE account_id=?",
                "SELECT count(*) FROM notification_notifications WHERE user_id=?",
                "SELECT count(*) FROM livestock_observations WHERE author_id=?",
                "SELECT count(*) FROM livestock_farms WHERE farmer_id=?",
                "SELECT count(*) FROM livestock_inventory_capacity WHERE owner_id=?",
                "SELECT count(*) FROM linking_links WHERE farmer_id=?",
                "SELECT count(*) FROM linking_invitations WHERE farmer_id=?",
                "SELECT count(*) FROM linking_capacity WHERE vet_id=?",
                "SELECT count(*) FROM care_records WHERE veterinarian_id=?",
                "SELECT count(*) FROM care_appointments WHERE veterinarian_id=?",
                "SELECT count(*) FROM subscriptions_subscriptions WHERE account_id=?",
                "SELECT count(*) FROM subscriptions_payments WHERE account_id=?"}) {
            assertThat(jdbc.queryForObject(query, Integer.class, admin)).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM care_records WHERE id=? AND appointment_id IS NULL", Integer.class, preservedRecord)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT active_links FROM linking_capacity WHERE vet_id=?", Integer.class, vet)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM care_appointments WHERE origin_attention_id=?", Integer.class, record)).isZero();
    }
}
