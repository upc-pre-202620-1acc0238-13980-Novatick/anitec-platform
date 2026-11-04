package com.anitec.backend;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.OffsetDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class VerificationWindowMigrationTests {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    DriverManagerDataSource database() {
        String name="verification_"+UUID.randomUUID().toString().replace("-","");
        new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())).execute("CREATE DATABASE "+name);
        return new DriverManagerDataSource("jdbc:postgresql://"+postgres.getHost()+":"+postgres.getMappedPort(5432)+"/"+name,postgres.getUsername(),postgres.getPassword());
    }
    Flyway flyway(DriverManagerDataSource db,String target) {
        return Flyway.configure().dataSource(db).target(target).placeholders(Map.of("admin_email","retired@bootstrap.invalid","admin_password_hash","DISABLED-HISTORICAL-BOOTSTRAP","demo_seed","false")).load();
    }
    UUID legacy(JdbcTemplate j,int resends,boolean reliable,boolean noSent) {
        return j.queryForObject("INSERT INTO identity_accounts(email,password_hash,name,last_name,role,status,verification_code,verification_expires_at,verification_attempts,verification_resends,created_at,verification_sent_at) "
                + "VALUES (?,'unused','Test','Legacy','GANADERO','PENDIENTE','123456',"
                +(reliable?"now()+interval '5 minutes'":"now()+interval '9 minutes'")+",2,?,"
                +(reliable?"now()-interval '5 minutes'":"now()-interval '2 days'")+","
                +(noSent?"NULL":reliable?"now()-interval '5 minutes'":"now()-interval '1 minute'")+") RETURNING id",UUID.class,UUID.randomUUID()+"@example.invalid",resends);
    }
    @Test void v13ContainsOnlyLastEmissionAndCountersNotWindowHistory() {
        var db=database();flyway(db,"13").migrate();var j=new JdbcTemplate(db);UUID id=legacy(j,5,false,false);
        assertThat(j.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name='identity_accounts' AND column_name IN ('verification_window_started_at','verification_policy_migrated_at')",Integer.class)).isZero();
        assertThat(j.queryForObject("SELECT verification_resends FROM identity_accounts WHERE id=?",Integer.class,id)).isEqualTo(5);
    }
    @Test void freshSchemaInstallsV14AndRepeatMigrationDoesNotRestartPolicy() {
        var db=database();flyway(db,"14").migrate();var j=new JdbcTemplate(db);
        UUID id=legacy(j,0,true,false);
        var transition=j.queryForObject("SELECT verification_policy_migrated_at FROM identity_accounts WHERE id=?",OffsetDateTime.class,id);
        assertThat(transition).isNotNull();assertThat(flyway(db,"14").migrate().migrationsExecuted).isZero();
        assertThat(j.queryForObject("SELECT verification_policy_migrated_at FROM identity_accounts WHERE id=?",OffsetDateTime.class,id)).isEqualTo(transition);
    }
    @Test void reliableInitialEmissionReconstructsWindowWithoutResettingAttempts() {
        var db=database();flyway(db,"13").migrate();var j=new JdbcTemplate(db);UUID id=legacy(j,0,true,false);
        var before=j.queryForMap("SELECT * FROM identity_accounts WHERE id=?",id);
        assertThat(flyway(db,"14").migrate().migrationsExecuted).isEqualTo(1);
        var after=j.queryForMap("SELECT * FROM identity_accounts WHERE id=?",id);
        assertThat(after.get("verification_window_started_at")).isEqualTo(before.get("verification_sent_at"));
        for(String field:List.of("id","verification_code","verification_attempts","verification_expires_at","verification_sent_at","verification_resends","created_at"))
            assertThat(after.get(field).equals(before.get(field))).as(field+" preserved").isTrue();
    }
    @Test void ambiguousHistoricalResendsKeepCodeAndAttemptsWithoutInventingWindowStart() {
        var db=database();flyway(db,"13").migrate();var j=new JdbcTemplate(db);UUID id=legacy(j,5,false,false);
        var before=j.queryForMap("SELECT * FROM identity_accounts WHERE id=?",id);flyway(db,"14").migrate();
        var after=j.queryForMap("SELECT * FROM identity_accounts WHERE id=?",id);
        assertThat(after.get("verification_window_started_at")).isNull();assertThat(after.get("verification_policy_migrated_at")).isNotNull();
        for(String field:List.of("id","verification_code","verification_attempts","verification_expires_at","verification_sent_at","verification_resends"))
            assertThat(after.get(field).equals(before.get(field))).as(field+" preserved").isTrue();
    }
    @Test void missingLastEmissionDoesNotCauseInventedHistoricalDatesOrAccountRecreation() {
        var db=database();flyway(db,"13").migrate();var j=new JdbcTemplate(db);UUID id=legacy(j,5,false,true);flyway(db,"14").migrate();
        assertThat(j.queryForObject("SELECT verification_window_started_at FROM identity_accounts WHERE id=?",OffsetDateTime.class,id)).isNull();
        assertThat(j.queryForObject("SELECT verification_sent_at FROM identity_accounts WHERE id=?",OffsetDateTime.class,id)).isNull();
        assertThat(j.queryForObject("SELECT verification_attempts FROM identity_accounts WHERE id=?",Integer.class,id)).isEqualTo(2);
        assertThat(j.queryForObject("SELECT count(*) FROM identity_accounts WHERE id=?",Integer.class,id)).isEqualTo(1);
    }
    @Test void invalidHistoricalCountersStopMigrationWithoutDeletingAccounts() {
        var db=database();flyway(db,"13").migrate();var j=new JdbcTemplate(db);UUID id=legacy(j,6,false,false);
        assertThatThrownBy(()->flyway(db,"14").migrate()).hasStackTraceContaining("ck_verification_recovery_counters");
        assertThat(j.queryForObject("SELECT verification_resends FROM identity_accounts WHERE id=?",Integer.class,id)).isEqualTo(6);
        assertThat(j.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='14' AND success",Integer.class)).isZero();
    }
}
