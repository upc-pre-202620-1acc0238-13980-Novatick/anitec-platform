package com.anitec.backend;

import com.anitec.backend.identity.domain.AccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.mock.mockito.SpyBean;
import com.anitec.backend.notification.infrastructure.ConsolePushNotificationAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "email.delivery.mode=disabled", "email.delivery.worker-enabled=false", "resend.api-key=",
        "identity.password-recovery.enabled=false", "payments.simulation-enabled=false",
        "spring.flyway.placeholders.demo_seed=false", "logging.level.root=ERROR",
        "logging.level.com.anitec.backend.shared.interfaceapi.GlobalExceptionHandler=OFF"})
class MvpContractStabilizationTests {
    @Container @ServiceConnection static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired AccountRepository accounts;
    @Autowired JdbcTemplate jdbc;
    @SpyBean ConsolePushNotificationAdapter push;
    final HttpClient http=HttpClient.newHttpClient();
    // Never put bearer credentials into generated toString/assertion output.
    static class User { String email,token; UUID id; }
    record Response(int status,JsonNode body) { JsonNode data(){return body.path("data");} }
    record Scenario(User farmer,User vet,String farm,String animal,String link) { }

    Response call(String method,String path,Object payload,User user) {
        try {
            var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path))
                    .header("Content-Type","application/json");
            if(user!=null)request.header("Authorization","Bearer "+user.token);
            request.method(method,payload==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
            var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(),response.body().isBlank()?json.createObjectNode():json.readTree(response.body()));
        } catch(Exception failure){throw new IllegalStateException("HTTP contract request failed",failure);}
    }
    String created(Response response){assertThat(response.status()).isEqualTo(201);return response.data().path("id").asText();}
    User user(String role) {
        var u=new User();u.email=UUID.randomUUID()+"@example.invalid";
        String password="Isolated-"+UUID.randomUUID()+"aA1!";
        assertThat(call("POST","/auth/register",Map.of("name",role.equals("VETERINARIO")?"Veterinario":"Ganadero","lastName","Prueba",
                "email",u.email,"password",password,"role",role),null).status()).isEqualTo(201);
        var account=accounts.findByEmail(u.email).orElseThrow();u.id=account.getId();
        assertThat(call("POST","/auth/verify",Map.of("email",u.email,"code",TestVerificationCodes.code(account)),null).status()).isEqualTo(200);
        var login=call("POST","/auth/login",Map.of("email",u.email,"password",password),null);
        assertThat(login.status()).isEqualTo(200);u.token=login.data().path("accessToken").asText();return u;
    }
    String species(User u){return call("GET","/species",null,u).data().get(0).path("id").asText();}
    String farm(User u,String location){var data=new HashMap<String,Object>();data.put("name","Finca de prueba");data.put("location",location);return created(call("POST","/farms",data,u));}
    String animal(User u,String farm){return created(call("POST","/animals",Map.of("farmId",farm,"speciesId",species(u),"sex","HEMBRA","name","Luna"),u));}
    String invite(User farmer,User vet){var r=call("POST","/invitations",Map.of("vetEmail",vet.email),farmer);assertThat(r.status()).isEqualTo(201);return r.data().path("invitationId").asText();}
    String link(User farmer,User vet){String invitation=invite(farmer,vet);assertThat(call("PUT","/invitations/"+invitation+"/respond",Map.of("action","ACCEPT"),vet).status()).isEqualTo(200);
        var r=call("GET","/vet-links",null,farmer);return java.util.stream.StreamSupport.stream(r.data().path("links").spliterator(),false)
                .filter(n->n.path("vetId").asText().equals(vet.id.toString())).findFirst().orElseThrow().path("linkId").asText();}
    Scenario scenario(String location){var f=user("GANADERO");var v=user("VETERINARIO");String farm=farm(f,location);return new Scenario(f,v,farm,animal(f,farm),link(f,v));}
    Map<String,Object> attention(String animal){return new HashMap<>(Map.of("animalId",animal,"title","Consulta","description","Evaluación clínica",
            "attentionDate",LocalDate.now(ZoneOffset.UTC).minusDays(1).toString()));}
    String visit(Scenario s){return created(call("POST","/visits/schedule",Map.of("animalId",s.animal,"scheduledAt","2035-06-01T14:30:00Z","reason","Control"),s.vet));}
    Map<String,Long> clinicalCounts(){var result=new LinkedHashMap<String,Long>();for(String table:List.of("care_records","care_treatments","care_vaccinations","care_instruction_versions","notification_notifications"))
        result.put(table,jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class));return result;}

    @ParameterizedTest @ValueSource(strings={"treatments","vaccinations"})
    void nullClinicalListElementsAreValidationErrorsWithoutPartialWrites(String field) {
        var s=scenario(null);String visit=visit(s);var payload=attention(s.animal);payload.put("visitId",visit);payload.put("instructions","No debe persistir");
        Object valid=field.equals("treatments")?Map.of("description","Tratamiento","appliedDate","2020-01-01"):Map.of("name","Vacuna","appliedDate","2020-01-01");
        payload.put(field,Arrays.asList(valid,null));var before=clinicalCounts();
        var response=call("POST","/attentions",payload,s.vet);
        assertThat(clinicalCounts()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT status FROM care_appointments WHERE id=?",String.class,UUID.fromString(visit))).isEqualTo("PROGRAMADA");
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().path("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.body().path("details").toString()).contains(field);
    }

    Map<String,Object> editable(JsonNode detail) {
        var data=new HashMap<String,Object>();
        for(String field:List.of("code","speciesId","sex","name","breed","birthDate","photoUrl"))
            data.put(field,detail.path(field).isNull()?null:detail.path(field).asText());
        data.put("version",detail.path("version").asLong());return data;
    }

    @Test void animalDetailRoundTripPreservesUnchangedFieldsAndRejectsStaleVersion() {
        var f=user("GANADERO");String farm=farm(f,null),species=species(f);
        String id=created(call("POST","/animals",Map.of("farmId",farm,"speciesId",species,"sex","HEMBRA",
                "code","BOV-013","name","Luna","breed","Holstein","birthDate","2020-01-02","photoUrl","https://example.invalid/animal.jpg"),f));
        var initial=call("GET","/animals/"+id,null,f);assertThat(initial.status()).isEqualTo(200);
        assertThat(initial.data().path("speciesId").asText()).isEqualTo(species);
        assertThat(initial.data().path("photoUrl").asText()).isEqualTo("https://example.invalid/animal.jpg");
        var edited=editable(initial.data());edited.put("name","Luna actualizada");
        assertThat(call("PUT","/animals/"+id,edited,f).status()).isEqualTo(200);
        var after=call("GET","/animals/"+id,null,f).data();
        for(String field:List.of("id","farmId","code","speciesId","photoUrl","sex","breed","birthDate"))
            assertThat(after.path(field)).as(field).isEqualTo(initial.data().path(field));
        assertThat(after.path("name").asText()).isEqualTo("Luna actualizada");
        assertThat(after.path("version").asLong()).isGreaterThan(initial.data().path("version").asLong());
        var stale=call("PUT","/animals/"+id,edited,f);
        assertThat(stale.status()).isEqualTo(409);assertThat(stale.body().path("errorCode").asText()).isEqualTo("ANIMAL_VERSION_CONFLICT");
        assertThat(call("GET","/animals/"+id,null,f).data()).isEqualTo(after);
        var change=editable(after);String anotherSpecies=call("GET","/species",null,f).data().get(1).path("id").asText();
        change.put("speciesId",anotherSpecies);change.put("photoUrl","https://example.invalid/updated.jpg");
        assertThat(call("PUT","/animals/"+id,change,f).status()).isEqualTo(200);
        var finalRead=call("GET","/animals/"+id,null,f).data();
        assertThat(finalRead.path("speciesId").asText()).isEqualTo(anotherSpecies);
        assertThat(finalRead.path("photoUrl").asText()).isEqualTo("https://example.invalid/updated.jpg");
        assertThat(finalRead.path("birthDate")).isEqualTo(initial.data().path("birthDate"));
        change=editable(finalRead);change.put("photoUrl",null);
        assertThat(call("PUT","/animals/"+id,change,f).status()).isEqualTo(200);
        assertThat(call("GET","/animals/"+id,null,f).data().path("photoUrl").isNull()).isTrue();
    }

    @Test void animalDetailOptionalPhotoAndNewFieldsRespectExistingScope() {
        var s=scenario(null);var stranger=user("GANADERO");
        var detail=call("GET","/animals/"+s.animal,null,s.farmer);assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.data().path("photoUrl").isNull()).isTrue();
        assertThat(detail.data().path("speciesId").asText()).isNotBlank();
        assertThat(call("GET","/animals/"+s.animal,null,s.vet).data()).isEqualTo(detail.data());
        assertThat(call("GET","/animals/"+s.animal,null,stranger).status()).isEqualTo(403);
        assertThat(call("GET","/animals/"+s.animal,null,null).status()).isEqualTo(401);
        assertThat(call("PUT","/animals/"+s.animal,editable(detail.data()),s.vet).status()).isEqualTo(403);
        assertThat(call("PUT","/animals/"+s.animal,editable(detail.data()),stranger).status()).isEqualTo(403);
        assertThat(call("DELETE","/vet-links/"+s.link,null,s.farmer).status()).isEqualTo(200);
        assertThat(call("GET","/animals/"+s.animal,null,s.vet).status()).isEqualTo(403);
        assertThat(call("GET","/animals/"+s.animal,null,s.farmer).data()).isEqualTo(detail.data());
    }

    @ParameterizedTest @ValueSource(strings={"GANADERO","VETERINARIO"})
    void profilePutThenGetPreservesProtectedFieldsAndOtherAccounts(String role) {
        var u=user(role);var other=user(role);var initial=call("GET","/account/me",null,u).data();var victim=call("GET","/account/me",null,other).data();
        var update=new HashMap<String,Object>(Map.of("name","Nombre actualizado","lastName","Apellido","photoUrl","https://example.invalid/profile.jpg",
                "address","Dirección local","dni","12345678","phone","900000000"));
        update.put("id",other.id);update.put("email",other.email);update.put("role",role.equals("GANADERO")?"VETERINARIO":"GANADERO");
        var response=call("PUT","/account/me",update,u);assertThat(response.status()).isEqualTo(200);
        var read=call("GET","/account/me",null,u);assertThat(read.status()).isEqualTo(200);assertThat(read.data()).isEqualTo(response.data());
        for(String field:List.of("id","email","role","emailVerified","createdAt"))assertThat(read.data().path(field)).isEqualTo(initial.path(field));
        for(String field:List.of("name","lastName","photoUrl","address","dni","phone"))assertThat(read.data().path(field).asText()).isEqualTo(update.get(field));
        assertThat(call("GET","/account/me",null,other).data()).isEqualTo(victim);
        update.put("name"," ");assertThat(call("PUT","/account/me",update,u).status()).isEqualTo(400);
        assertThat(call("GET","/account/me",null,u).data()).isEqualTo(read.data());
        assertThat(call("PUT","/account/me",update,null).status()).isEqualTo(401);
    }

    @Test void farmsListAndDetailRespectOwnerLinkedVetAndThirdPartyIsolation() {
        var f=user("GANADERO");var other=user("GANADERO");var vet=user("VETERINARIO");var unlinked=user("VETERINARIO");
        String first=farm(f,"Camino local"),second=farm(f,null),foreign=farm(other,"Ubicación ajena");link(f,vet);
        for(User actor:List.of(f,vet)) {
            var list=call("GET","/farms",null,actor);assertThat(list.status()).isEqualTo(200);
            assertThat(java.util.stream.StreamSupport.stream(list.data().spliterator(),false).map(n->n.path("id").asText()).toList()).containsExactlyInAnyOrder(first,second);
            var detail=call("GET","/farms/"+first,null,actor);assertThat(detail.status()).isEqualTo(200);
            assertThat(detail.data().path("ownerId").asText()).isEqualTo(f.id.toString());assertThat(detail.data().path("location").asText()).isEqualTo("Camino local");
            assertThat(Instant.parse(detail.data().path("createdAt").asText())).isNotNull();
            assertThat(call("GET","/farms/"+second,null,actor).data().path("location").isNull()).isTrue();
            assertThat(call("GET","/farms/"+foreign,null,actor).status()).isEqualTo(403);
        }
        assertThat(call("GET","/farms",null,unlinked).data().isEmpty()).isTrue();
        assertThat(call("GET","/farms",null,other).data()).hasSize(1);
        for(User actor:List.of(other,unlinked))assertThat(call("GET","/farms/"+first,null,actor).status()).isEqualTo(403);
        assertThat(call("GET","/farms/"+UUID.randomUUID(),null,f).status()).isEqualTo(404);
        assertThat(call("GET","/farms",null,null).status()).isEqualTo(401);
        assertThat(call("GET","/farms/"+first,null,null).status()).isEqualTo(401);
    }

    @Test void rejectingInvitationPreservesCapacityAndCanBeInvitedAgain() {
        var f=user("GANADERO");var v=user("VETERINARIO");var other=user("VETERINARIO");String id=invite(f,v);
        var before=call("GET","/vet-links/capacity",null,v).data();
        assertThat(call("PUT","/invitations/"+id+"/respond",Map.of("action","REJECT"),f).status()).isEqualTo(403);
        assertThat(call("PUT","/invitations/"+id+"/respond",Map.of("action","REJECT"),other).status()).isEqualTo(403);
        assertThat(call("PUT","/invitations/"+id+"/respond",Map.of("action","REJECT"),null).status()).isEqualTo(401);
        var rejected=call("PUT","/invitations/"+id+"/respond",Map.of("action","REJECT"),v);assertThat(rejected.status()).isEqualTo(200);
        assertThat(rejected.data().path("invitationId").asText()).isEqualTo(id);assertThat(rejected.data().path("status").asText()).isEqualTo("RECHAZADA");
        assertThat(call("GET","/vet-links/capacity",null,v).data()).isEqualTo(before);
        assertThat(call("GET","/invitations/pending",null,v).data().isEmpty()).isTrue();
        assertThat(call("GET","/vet-links",null,f).data().path("activeLinksCount").asLong()).isZero();
        var again=call("PUT","/invitations/"+id+"/respond",Map.of("action","REJECT"),v);assertThat(again.status()).isEqualTo(409);
        assertThat(again.body().path("errorCode").asText()).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(invite(f,v)).isNotEqualTo(id);
    }

    @Test void firstInstructionsEndpointPreservesAuthorshipVersionsAndOwnerReadAfterRevocation() {
        var s=scenario(null);String attention=created(call("POST","/attentions",attention(s.animal),s.vet));
        var empty=call("GET","/attentions/"+attention+"/instructions",null,s.farmer);assertThat(empty.status()).isEqualTo(200);assertThat(empty.data().isMissingNode()).isTrue();
        var otherVet=user("VETERINARIO");link(s.farmer,otherVet);var stranger=user("GANADERO");String path="/attentions/"+attention+"/instructions";
        for(User forbidden:List.of(s.farmer,otherVet,stranger))assertThat(call("POST",path,Map.of("content","No autorizado"),forbidden).status()).isEqualTo(403);
        assertThat(call("POST",path,Map.of("content","Contenido"),null).status()).isEqualTo(401);
        assertThat(call("POST",path,Map.of("content"," "),s.vet).status()).isEqualTo(400);
        var saved=call("POST",path,Map.of("content","Mantener hidratación"),s.vet);assertThat(saved.status()).isEqualTo(201);
        assertThat(saved.data().path("content").asText()).isEqualTo("Mantener hidratación");assertThat(saved.data().path("authorName").asText()).isEqualTo("Veterinario Prueba");
        assertThat(Instant.parse(saved.data().path("updatedAt").asText())).isNotNull();
        assertThat(call("GET",path,null,s.farmer).data()).isEqualTo(saved.data());
        assertThat(call("GET",path,null,stranger).status()).isEqualTo(403);
        var duplicate=call("POST",path,Map.of("content","Duplicado"),s.vet);assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.body().path("errorCode").asText()).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM care_instruction_versions WHERE care_record_id=?",Long.class,UUID.fromString(attention))).isEqualTo(1);
        assertThat(call("DELETE","/vet-links/"+s.link,null,s.farmer).status()).isEqualTo(200);
        assertThat(call("POST",path,Map.of("content","Revocado"),s.vet).status()).isEqualTo(403);
        assertThat(call("GET",path,null,s.vet).status()).isEqualTo(403);
        assertThat(call("GET",path,null,s.farmer).data()).isEqualTo(saved.data());
    }

    @ParameterizedTest @ValueSource(strings={"omitted","empty","null"})
    void absentEmptyAndNullClinicalListsRemainOptional(String variant) {
        var s=scenario(null);var payload=attention(s.animal);
        if(!variant.equals("omitted")){payload.put("treatments",variant.equals("empty")?List.of():null);payload.put("vaccinations",variant.equals("empty")?List.of():null);}
        var response=call("POST","/attentions",payload,s.vet);assertThat(response.status()).isEqualTo(201);
        assertThat(response.data().path("treatments").isEmpty()).isTrue();assertThat(response.data().path("vaccinations").isEmpty()).isTrue();
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.NullAndEmptySource @ValueSource(strings={" ","Camino local km 3"})
    void visitNoticeIsSelfContainedPrivateAndDoesNotPromiseFarmerAgenda(String location) {
        var s=scenario(location);visit(s);var other=user("GANADERO");
        var notices=call("GET","/notifications",null,s.farmer);assertThat(notices.status()).isEqualTo(200);
        var notice=java.util.stream.StreamSupport.stream(notices.data().path("content").spliterator(),false)
                .filter(n->n.path("type").asText().equals("VISIT_SCHEDULED")).findFirst().orElseThrow();
        assertThat(notice.path("message").asText()).contains("Luna","ANI-000001","Veterinario Prueba","01/06/2035 09:30","America/Lima","Finca de prueba",
                location==null||location.isBlank()?"Ubicación no registrada":location);
        assertThat(notice.path("message").asText().toLowerCase(Locale.ROOT)).doesNotContain("agenda");
        var fields=new HashSet<String>();notice.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("id","type","title","message","read","createdAt");
        assertThat(call("GET","/notifications",null,other).data().path("content").isEmpty()).isTrue();
        assertThat(call("PATCH","/notifications/"+notice.path("id").asText()+"/read",null,other).status()).isEqualTo(403);
        assertThat(call("PATCH","/notifications/"+notice.path("id").asText()+"/read",null,s.farmer).status()).isEqualTo(200);
        assertThat(call("GET","/visits",null,s.farmer).status()).isEqualTo(403);
        var message=org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(push).send(org.mockito.ArgumentMatchers.eq(s.farmer.id),org.mockito.ArgumentMatchers.eq("Visita veterinaria programada"),message.capture());
        assertThat(message.getValue()).isEqualTo("Hay un nuevo aviso de visita o control en su bandeja de notificaciones.");
    }

    @Test void followUpHasAnInformativeControlNotice() {
        var s=scenario(null);String attention=created(call("POST","/attentions",attention(s.animal),s.vet));
        assertThat(call("POST","/visits/follow-up",Map.of("attentionId",attention,"scheduledAt","2035-06-01T14:30:00Z"),s.vet).status()).isEqualTo(201);
        var list=call("GET","/notifications",null,s.farmer).data().path("content");
        var notice=java.util.stream.StreamSupport.stream(list.spliterator(),false).filter(n->n.path("type").asText().equals("VISIT_SCHEDULED")).findFirst().orElseThrow();
        assertThat(notice.path("title").asText()).isEqualTo("Control veterinario programado");
        assertThat(notice.path("message").asText()).contains("un control","Luna","01/06/2035 09:30","Ubicación no registrada");
        assertThat(notice.path("message").asText().toLowerCase(Locale.ROOT)).doesNotContain("agenda");
    }
}
