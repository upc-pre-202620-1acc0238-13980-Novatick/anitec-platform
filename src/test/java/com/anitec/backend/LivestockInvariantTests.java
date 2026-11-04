package com.anitec.backend;

import com.anitec.backend.identity.domain.AccountRepository;
import com.anitec.backend.livestock.application.LivestockService;
import com.anitec.backend.livestock.domain.Animal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.net.URI;
import java.net.http.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"resend.api-key=", "payments.simulation-enabled=false"})
class LivestockInvariantTests {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired AccountRepository accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired LivestockService service;
    @Autowired PlatformTransactionManager transactions;
    private final HttpClient http = HttpClient.newHttpClient();
    // Authentication remains in memory, not in record toString or assertion output.
    static class User { String email, token, farm, species; UUID id; }
    record Response(int status, JsonNode body) { JsonNode data() { return body.path("data"); } }
    Response call(String method, String path, Object payload, User user) {
        try {
            var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                    .header("Content-Type", "application/json");
            if (user != null) b.header("Authorization", "Bearer " + user.token);
            b.method(method, payload == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)));
            var response = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(response.body()));
        } catch (Exception ex) { throw new IllegalStateException("HTTP " + method + " " + path, ex); }
    }
    String created(Response response) { assertThat(response.status()).isEqualTo(201); return response.data().path("id").asText(); }
    User user(String role) {
        var u = new User(); u.email = "livestock-" + UUID.randomUUID() + "@example.invalid";
        String password = "Test-" + UUID.randomUUID() + "aA1!";
        assertThat(call("POST", "/auth/register", Map.of("email",u.email,"password",password,"role",role,"name","Test","lastName","Inventory"), null).status()).isEqualTo(201);
        var account = accounts.findByEmail(u.email).orElseThrow(); u.id = account.getId();
        assertThat(call("POST", "/auth/verify", Map.of("email",u.email,"code",account.getVerificationCode()),null).status()).isEqualTo(200);
        var login = call("POST","/auth/login",Map.of("email",u.email,"password",password),null);
        assertThat(login.status()).isEqualTo(200); u.token = login.data().path("accessToken").asText();
        if (role.equals("GANADERO")) {
            u.farm = farm(u); u.species = call("GET","/species",null,u).data().get(0).path("id").asText();
        }
        return u;
    }
    String farm(User u) { return created(call("POST","/farms",Map.of("name","Test farm"),u)); }
    Map<String,Object> payload(User u, String farm, String code) {
        var p = new HashMap<String,Object>(); p.put("farmId",farm); p.put("speciesId",u.species); p.put("sex","HEMBRA");
        if (code != null) p.put("code",code); return p;
    }
    Response register(User u,String code) { return call("POST","/animals",payload(u,u.farm,code),u); }
    Map<String,Object> edit(User u, String code, long version) { return Map.of("code",code,"speciesId",u.species,"sex","HEMBRA","version",version); }
    JsonNode animal(User u,String id) { var r=call("GET","/animals/"+id,null,u); assertThat(r.status()).isEqualTo(200); return r.data(); }
    long changes(String id) { return jdbc.queryForObject("SELECT count(*) FROM livestock_code_changes WHERE animal_id=?",Long.class,UUID.fromString(id)); }
    <T> List<T> together(int count, IntFunction<T> action) throws Exception {
        var ready=new CountDownLatch(count); var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(count)) {
            var calls=java.util.stream.IntStream.range(0,count).mapToObj(i->executor.submit(()->{
                ready.countDown(); if(!start.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("start timeout"); return action.apply(i);
            })).toList();
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); start.countDown();
            var results=new ArrayList<T>(); for(var future:calls) results.add(future.get(45,TimeUnit.SECONDS)); return results;
        }
    }
    @Test void automaticFirstAndSequentialCodesAcrossFarms() {
        var u=user("GANADERO"); var first=register(u,null); created(first);
        assertThat(first.data().path("code").asText()).isEqualTo("ANI-000001");
        var p=payload(u,farm(u),""); var second=call("POST","/animals",p,u); created(second);
        assertThat(second.data().path("code").asText()).isEqualTo("ANI-000002");
        p=new HashMap<>(payload(u,u.farm,null));p.put("code",null);
        assertThat(call("POST","/animals",p,u).data().path("code").asText()).isEqualTo("ANI-000003");
        call("DELETE","/animals/"+first.data().path("id").asText(),null,u);
        assertThat(register(u,null).data().path("code").asText()).isEqualTo("ANI-000004");
    }
    @Test void manualNormalizationPreservesZerosAndOwnerIsolation() {
        var u=user("GANADERO"); var r=register(u,"  ab-0012  ");created(r);
        assertThat(r.data().path("code").asText()).isEqualTo("AB-0012");
        created(register(u,"001"));created(register(u,"1"));
        assertThat(register(u,"AB-0012").status()).isEqualTo(409);
        var other=user("GANADERO");created(register(other,"ab-0012"));
        assertThat(register(u,null).data().path("code").asText()).isEqualTo("ANI-000001");
        assertThat(register(other,null).data().path("code").asText()).isEqualTo("ANI-000001");
    }
    @Test void duplicateAcrossFarmsIsRejected() {
        var u=user("GANADERO");created(register(u,"TAG-1"));
        var second=call("POST","/animals",payload(u,farm(u),"TAG-1"),u);
        assertThat(second.status()).isEqualTo(409);
        assertThat(second.body().path("errorCode").asText()).isEqualTo("DUPLICATE_ANIMAL_CODE");
    }
    @Test void invalidManualCodesAreNotReplacedAutomatically() {
        var u=user("GANADERO");
        for(String code:List.of("---","A B","á-1","ß","A_1","A".repeat(31))) assertThat(register(u,code).status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM livestock_animals WHERE owner_id=?",Long.class,u.id)).isZero();
    }
    @Test void unknownSexIsAcceptedAlongsideExistingValues() {
        var u=user("GANADERO");var p=payload(u,u.farm,"UNKNOWN-1");p.put("sex","DESCONOCIDO");String id=created(call("POST","/animals",p,u));
        assertThat(animal(u,id).path("sex").asText()).isEqualTo("DESCONOCIDO");
    }
    @Test void deactivationKeepsCodeAndRejectsCodeChanges() {
        var u=user("GANADERO");String id=created(register(u,"KEEP"));
        assertThat(call("DELETE","/animals/"+id,null,u).status()).isEqualTo(200);
        assertThat(call("POST","/animals",payload(u,farm(u),"KEEP"),u).status()).isEqualTo(409);
        assertThat(call("PUT","/animals/"+id,edit(u,"NEW",animal(u,id).path("version").asLong()),u).status()).isEqualTo(409);
        assertThat(animal(u,id).path("code").asText()).isEqualTo("KEEP");
    }
    @Test void historicalReservationsRecoveryAndNoOpAudit() {
        var u=user("GANADERO");var a=register(u,"OLD");String id=created(a); long version=a.data().path("version").asLong();
        var updated=call("PUT","/animals/"+id,edit(u,"new",version),u);assertThat(updated.status()).isEqualTo(200);
        assertThat(changes(id)).isEqualTo(1);
        assertThat(register(u,"OLD").status()).isEqualTo(409);
        var recovered=call("PUT","/animals/"+id,edit(u,"old",updated.data().path("version").asLong()),u);
        assertThat(recovered.status()).isEqualTo(200);assertThat(changes(id)).isEqualTo(2);
        assertThat(call("PUT","/animals/"+id,edit(u,"  old  ",recovered.data().path("version").asLong()),u).status()).isEqualTo(200);
        assertThat(changes(id)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT author_id FROM livestock_code_changes WHERE animal_id=?",UUID.fromString(id)))
                .allSatisfy(row->assertThat(row.get("author_id")).isEqualTo(u.id));
        assertThat(call("PUT","/animals/"+id,edit(u," ",animal(u,id).path("version").asLong()),u).status()).isEqualTo(400);
    }
    @Test void automaticSkipsReservedManualAndHistoricalCodesAndExpandsWidth() {
        var u=user("GANADERO");var a=register(u,"ANI-000001");String id=created(a);
        assertThat(call("PUT","/animals/"+id,edit(u,"TAG",a.data().path("version").asLong()),u).status()).isEqualTo(200);
        assertThat(register(u,null).data().path("code").asText()).isEqualTo("ANI-000002");
        jdbc.update("UPDATE livestock_code_counters SET next_value=999999 WHERE owner_id=?",u.id);
        assertThat(register(u,null).data().path("code").asText()).isEqualTo("ANI-999999");
        assertThat(register(u,null).data().path("code").asText()).isEqualTo("ANI-1000000");
    }
    @Test void concurrentAutomaticRegistrationHasDistinctCodesAndCorrectCapacity() throws Exception {
        var u=user("GANADERO");var results=together(6,i->register(u,null));
        assertThat(results).allSatisfy(r->assertThat(r.status()).isEqualTo(201));
        assertThat(results.stream().map(r->r.data().path("code").asText()).toList()).doesNotHaveDuplicates();
        assertThat(call("GET","/animals/capacity",null,u).data().path("activeAnimals").asInt()).isEqualTo(6);
    }
    @Test void concurrentManualRegistrationAcrossFarmsHasOneWinner() throws Exception {
        var u=user("GANADERO");String second=farm(u);var results=together(2,i->call("POST","/animals",payload(u,i==0?u.farm:second,"SAME"),u));
        assertThat(results).extracting(Response::status).containsExactlyInAnyOrder(201,409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM livestock_animals WHERE owner_id=?",Long.class,u.id)).isEqualTo(1);
    }
    @Test void concurrentEditsAndStaleFormsDoNotOverwrite() throws Exception {
        var u=user("GANADERO");var a=register(u,"BASE");String id=created(a);long version=a.data().path("version").asLong();
        var results=together(2,i->call("PUT","/animals/"+id,edit(u,"EDIT-"+i,version),u));
        assertThat(results).extracting(Response::status).containsExactlyInAnyOrder(200,409);
        assertThat(changes(id)).isEqualTo(1);
        assertThat(call("PUT","/animals/"+id,edit(u,"STALE",version),u).status()).isEqualTo(409);
        assertThat(changes(id)).isEqualTo(1);
    }
    @Test void failedEditLeavesNoReservationOrAudit() {
        var u=user("GANADERO");var a=register(u,"ORIGINAL");String id=created(a);
        var p=new HashMap<>(edit(u,"ABORTED",a.data().path("version").asLong()));p.put("birthDate",LocalDate.now().plusDays(2).toString());
        assertThat(call("PUT","/animals/"+id,p,u).status()).isEqualTo(400);
        assertThat(changes(id)).isZero();created(register(u,"ABORTED"));
        assertThat(animal(u,id).path("code").asText()).isEqualTo("ORIGINAL");
    }
    @Test void farmCountsOnlyItsActiveAnimalsForFarmerAndMultiOwnerVet() {
        var u=user("GANADERO");created(register(u,"A"));String inactive=created(register(u,"B"));
        call("DELETE","/animals/"+inactive,null,u);created(call("POST","/animals",payload(u,farm(u),"C"),u));
        var other=user("GANADERO");created(register(other,"D"));var vet=user("VETERINARIO");
        for(var farmer:List.of(u,other)) {
            var invite=call("POST","/invitations",Map.of("vetEmail",vet.email),farmer);
            assertThat(call("PUT","/invitations/"+invite.data().path("invitationId").asText()+"/respond",Map.of("action","ACCEPT"),vet).status()).isEqualTo(200);
        }
        assertThat(call("GET","/farms/"+u.farm+"/animals",null,u).data().path("totalActive").asLong()).isEqualTo(1);
        assertThat(call("GET","/farms/"+u.farm+"/animals",null,vet).data().path("totalActive").asLong()).isEqualTo(1);
        assertThat(call("GET","/animals",null,vet).data().path("totalActive").asLong()).isEqualTo(3);
    }
    @Test void changingVisibleCodePreservesClinicalAndObservationAssociations() {
        var u=user("GANADERO");var a=register(u,"BEFORE");String id=created(a);var vet=user("VETERINARIO");
        var invite=call("POST","/invitations",Map.of("vetEmail",vet.email),u);
        call("PUT","/invitations/"+invite.data().path("invitationId").asText()+"/respond",Map.of("action","ACCEPT"),vet);
        created(call("POST","/animals/"+id+"/observations",Map.of("text","Preserve"),u));
        String visit=created(call("POST","/visits/schedule",Map.of("animalId",id,"scheduledAt",java.time.Instant.now().plusSeconds(86400).toString()),vet));
        String attention=created(call("POST","/attentions",Map.of("animalId",id,"visitId",visit,"title","Care","description","Preserve","attentionDate",LocalDate.now().minusDays(1).toString()),vet));
        var current=animal(u,id);assertThat(call("PUT","/animals/"+id,edit(u,"AFTER",current.path("version").asLong()),u).status()).isEqualTo(200);
        assertThat(animal(u,id).path("observationsCount").asInt()).isEqualTo(1);
        assertThat(call("GET","/attentions/"+attention,null,u).data().path("animalId").asText()).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT animal_id FROM care_appointments WHERE id=?",UUID.class,UUID.fromString(visit))).isEqualTo(UUID.fromString(id));
    }
    @Test void onlyOwnerCanEditAndVersionIsRequired() {
        var u=user("GANADERO");String id=created(register(u,"OWNER"));var other=user("GANADERO");
        assertThat(call("PUT","/animals/"+id,edit(other,"INTRUDER",0),other).status()).isEqualTo(403);
        var p=new HashMap<>(edit(u,"VERSION",0));p.remove("version");assertThat(call("PUT","/animals/"+id,p,u).status()).isEqualTo(400);
    }
    @Test void transactionRollbackRestoresCodeVersionReservationsAndAudit() {
        var u=user("GANADERO");var a=register(u,"BEFORE");String id=created(a);long version=a.data().path("version").asLong();
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->{
            service.updateAnimal(u.id,UUID.fromString(id),"ROLLED-BACK",UUID.fromString(u.species),Animal.Sex.HEMBRA,null,null,null,null,version);
            throw new IllegalStateException("Intentional rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(animal(u,id).path("code").asText()).isEqualTo("BEFORE");
        assertThat(animal(u,id).path("version").asLong()).isEqualTo(version);
        assertThat(changes(id)).isZero();created(register(u,"ROLLED-BACK"));
    }
    @Test void failedAutomaticTransactionDoesNotLeaveSlotsOrReservations() {
        var u=user("GANADERO");
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->{
            service.registerAnimal(u.id,UUID.fromString(u.farm),null,UUID.fromString(u.species),Animal.Sex.DESCONOCIDO,null,null,null,null);
            throw new IllegalStateException("Intentional rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM livestock_animal_codes WHERE owner_id=?",Long.class,u.id)).isZero();
        assertThat(call("GET","/animals/capacity",null,u).data().path("activeAnimals").asInt()).isZero();
        var first=register(u,null);created(first);assertThat(first.data().path("code").asText()).isEqualTo("ANI-000001");
    }
}
