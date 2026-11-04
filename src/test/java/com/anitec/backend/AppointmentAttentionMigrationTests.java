package com.anitec.backend;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
class AppointmentAttentionMigrationTests {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private DriverManagerDataSource database() {
        String name = "cardinality_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        admin.execute("CREATE DATABASE " + name);
        String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + name;
        return new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
    }

    private Flyway flyway(DriverManagerDataSource db, String target) {
        return Flyway.configure().dataSource(db).target(target)
                .placeholders(Map.of("admin_email", "retired@bootstrap.invalid",
                        "admin_password_hash", "DISABLED-HISTORICAL-BOOTSTRAP", "demo_seed", "false"))
                .load();
    }

    private UUID appointment(JdbcTemplate jdbc, String state) {
        return jdbc.queryForObject("INSERT INTO care_appointments(animal_id,owner_id,veterinarian_id,type,scheduled_at,status) "
                        + "VALUES (?,?,?,'VISITA',now()+interval '1 day',?) RETURNING id", UUID.class,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), state);
    }

    private UUID attention(JdbcTemplate jdbc, UUID appointment) {
        return jdbc.queryForObject("INSERT INTO care_records(animal_id,owner_id,veterinarian_id,appointment_id,title,description,attention_date) "
                        + "VALUES (?,?,?,?,'Preserved record','Preserved description',CURRENT_DATE) RETURNING id", UUID.class,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), appointment);
    }

    private int uniqueConstraints(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid='care_records'::regclass "
                + "AND conname='uq_care_records_appointment' AND contype='u'", Integer.class);
    }

    @Test
    void previousSchemaHasForeignKeyWithoutAppointmentUniqueness() {
        var db = database();
        flyway(db, "11").migrate();
        var jdbc = new JdbcTemplate(db);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid='care_records'::regclass "
                + "AND contype='f'", Integer.class)).isEqualTo(1);
        assertThat(uniqueConstraints(jdbc)).isZero();
        UUID visit = appointment(jdbc, "COMPLETADA");
        attention(jdbc, visit);
        attention(jdbc, visit);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM care_records WHERE appointment_id=?", Integer.class, visit)).isEqualTo(2);
    }

    @Test
    void freshMigrationEnforcesUniqueAppointmentButAllowsMultipleNulls() {
        var db = database();
        flyway(db, "12").migrate();
        var jdbc = new JdbcTemplate(db);
        assertThat(uniqueConstraints(jdbc)).isEqualTo(1);
        UUID visit = appointment(jdbc, "COMPLETADA");
        attention(jdbc, visit);
        assertThatThrownBy(() -> attention(jdbc, visit)).isInstanceOf(DataIntegrityViolationException.class);
        attention(jdbc, null);
        attention(jdbc, null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM care_records", Integer.class)).isEqualTo(3);
        assertThat(flyway(db, "12").migrate().migrationsExecuted).isZero();
    }

    @Test
    void upgradeFromV11PreservesClinicalRecordsAndComplements() {
        var db = database();
        flyway(db, "11").migrate();
        var jdbc = new JdbcTemplate(db);
        UUID visit = appointment(jdbc, "COMPLETADA");
        UUID record = attention(jdbc, visit);
        UUID independent = attention(jdbc, null);
        jdbc.update("INSERT INTO care_treatments(care_record_id,description,applied_date) VALUES (?,'Preserved',CURRENT_DATE)", record);
        assertThat(flyway(db, "12").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(uniqueConstraints(jdbc)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM care_records WHERE id IN (?,?)", Integer.class, record, independent)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT description FROM care_treatments WHERE care_record_id=?", String.class, record)).isEqualTo("Preserved");
        assertThat(jdbc.queryForObject("SELECT appointment_id FROM care_records WHERE id=?", UUID.class, record)).isEqualTo(visit);
    }

    private void assertBlockedWithoutChangingRows(String state, int count, String message) {
        var db = database();
        flyway(db, "11").migrate();
        var jdbc = new JdbcTemplate(db);
        UUID visit = appointment(jdbc, state);
        for (int i = 0; i < count; i++) attention(jdbc, visit);
        assertThatThrownBy(() -> flyway(db, "12").migrate()).hasStackTraceContaining(message);
        assertThat(uniqueConstraints(jdbc)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM care_records WHERE appointment_id=?", Integer.class, visit)).isEqualTo(count);
        assertThat(jdbc.queryForObject("SELECT status FROM care_appointments WHERE id=?", String.class, visit)).isEqualTo(state);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='12' AND success", Integer.class)).isZero();
    }

    @Test
    void duplicateHistoryStopsMigrationWithoutDeletingOrReassigningRecords() {
        assertBlockedWithoutChangingRows("COMPLETADA", 2, "APPOINTMENT_ATTENTION_DUPLICATES");
    }

    @Test
    void completedWithoutAttentionStopsMigrationWithoutInventingClinicalData() {
        assertBlockedWithoutChangingRows("COMPLETADA", 0, "APPOINTMENT_ATTENTION_STATE_CONFLICT");
    }

    @Test
    void cancelledWithAttentionStopsMigrationWithoutAlteringHistory() {
        assertBlockedWithoutChangingRows("CANCELADA", 1, "APPOINTMENT_ATTENTION_STATE_CONFLICT");
    }
}
