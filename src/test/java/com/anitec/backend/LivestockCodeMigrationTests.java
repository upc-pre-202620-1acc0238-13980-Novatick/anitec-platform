package com.anitec.backend;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class LivestockCodeMigrationTests {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    DriverManagerDataSource database() {
        String name="livestock_"+UUID.randomUUID().toString().replace("-","");
        new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())).execute("CREATE DATABASE "+name);
        return new DriverManagerDataSource("jdbc:postgresql://"+postgres.getHost()+":"+postgres.getMappedPort(5432)+"/"+name,postgres.getUsername(),postgres.getPassword());
    }
    Flyway flyway(DriverManagerDataSource db,String target) {
        return Flyway.configure().dataSource(db).target(target).placeholders(Map.of("admin_email","retired@bootstrap.invalid","admin_password_hash","DISABLED-HISTORICAL-BOOTSTRAP","demo_seed","false")).load();
    }
    UUID farm(JdbcTemplate j,UUID owner) { return j.queryForObject("INSERT INTO livestock_farms(farmer_id,name) VALUES (?,'Test') RETURNING id",UUID.class,owner); }
    UUID animal(JdbcTemplate j,UUID owner,UUID farm,String code) {
        return j.queryForObject("INSERT INTO livestock_animals(owner_id,farm_id,species_id,code,sex) SELECT ?,?,id,?,'HEMBRA' FROM livestock_species LIMIT 1 RETURNING id",UUID.class,owner,farm,code);
    }
    @Test void previousSchemaUsesFarmUniquenessAndCannotStoreUnknownSex() {
        var db=database();flyway(db,"12").migrate();var j=new JdbcTemplate(db);UUID owner=UUID.randomUUID();
        UUID first=animal(j,owner,farm(j,owner),"SAME");animal(j,owner,farm(j,owner),"SAME");
        assertThat(j.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='livestock_animals'::regclass AND conname='unique_code_per_farm'",String.class)).contains("farm_id, code");
        assertThatThrownBy(()->j.update("UPDATE livestock_animals SET sex='DESCONOCIDO' WHERE id=?",first)).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void freshDatabaseEnforcesCurrentAndHistoricalCodesEvenWithoutApplicationChecks() {
        var db=database();flyway(db,"13").migrate();var j=new JdbcTemplate(db);UUID owner=UUID.randomUUID();UUID f=farm(j,owner);
        UUID id=animal(j,owner,f,"CODE-1");
        assertThatThrownBy(()->animal(j,owner,farm(j,owner),"CODE-1")).isInstanceOf(DataIntegrityViolationException.class);
        j.update("UPDATE livestock_animals SET code='CODE-2' WHERE id=?",id);
        assertThatThrownBy(()->animal(j,owner,f,"CODE-1")).isInstanceOf(DataIntegrityViolationException.class);
        j.update("UPDATE livestock_animals SET code='CODE-1' WHERE id=?",id);
        assertThat(j.queryForObject("SELECT count(*) FROM livestock_code_changes WHERE animal_id=?",Long.class,id)).isEqualTo(2);
        assertThatThrownBy(()->j.update("DELETE FROM livestock_animal_codes WHERE animal_id=?",id)).isInstanceOf(DataIntegrityViolationException.class);
        j.update("UPDATE livestock_animals SET status='INACTIVO' WHERE id=?",id);
        assertThatThrownBy(()->j.update("UPDATE livestock_animals SET code='OTHER' WHERE id=?",id)).isInstanceOf(DataIntegrityViolationException.class);
        UUID otherOwner=UUID.randomUUID();animal(j,otherOwner,farm(j,otherOwner),"CODE-1");
        assertThat(flyway(db,"13").migrate().migrationsExecuted).isZero();
    }
    @Test void upgradePreservesIdsRelationshipsAndSeedsOnlyCurrentReservations() {
        var db=database();flyway(db,"12").migrate();var j=new JdbcTemplate(db);UUID owner=UUID.randomUUID();UUID id=animal(j,owner,farm(j,owner),"ANI-000001");
        j.update("INSERT INTO livestock_observations(animal_id,author_id,text) VALUES (?,?,'Preserved')",id,owner);
        UUID record=j.queryForObject("INSERT INTO care_records(animal_id,owner_id,veterinarian_id,title,description,attention_date) VALUES (?, ?, ?, 'History','Preserve',CURRENT_DATE) RETURNING id",UUID.class,id,owner,UUID.randomUUID());
        assertThat(flyway(db,"13").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(j.queryForObject("SELECT animal_id FROM livestock_animal_codes WHERE owner_id=? AND code='ANI-000001'",UUID.class,owner)).isEqualTo(id);
        assertThat(j.queryForObject("SELECT count(*) FROM livestock_code_changes",Long.class)).isZero();
        assertThat(j.queryForObject("SELECT animal_id FROM care_records WHERE id=?",UUID.class,record)).isEqualTo(id);
        assertThat(j.queryForObject("SELECT count(*) FROM livestock_observations WHERE animal_id=?",Long.class,id)).isEqualTo(1);
        j.update("UPDATE livestock_animals SET sex='DESCONOCIDO' WHERE id=?",id);
    }
    void incompatible(String first,String second,String message) {
        var db=database();flyway(db,"12").migrate();var j=new JdbcTemplate(db);UUID owner=UUID.randomUUID();
        UUID id=animal(j,owner,farm(j,owner),first);if(second!=null) animal(j,owner,farm(j,owner),second);
        assertThatThrownBy(()->flyway(db,"13").migrate()).hasStackTraceContaining(message);
        assertThat(j.queryForObject("SELECT code FROM livestock_animals WHERE id=?",String.class,id)).isEqualTo(first);
        assertThat(j.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='13' AND success",Long.class)).isZero();
        assertThat(j.queryForObject("SELECT to_regclass('livestock_animal_codes')",String.class)).isNull();
    }
    @Test void normalizedDuplicatesStopWithoutRenamingHistory() { incompatible("abc","ABC","ANIMAL_CODE_DUPLICATES"); }
    @Test void invalidCharactersStopWithoutChangingCodes() {
        for(String code:List.of("A B","","A".repeat(31))) incompatible(code,null,"ANIMAL_CODE_INCOMPATIBLE");
    }
    @Test void nonCanonicalCodesRequireExplicitReview() { incompatible(" lower ",null,"ANIMAL_CODE_INCOMPATIBLE"); }
    @Test void sameOwnerDuplicatesAcrossFarmsStopSafely() { incompatible("SAME","SAME","ANIMAL_CODE_DUPLICATES"); }
}
