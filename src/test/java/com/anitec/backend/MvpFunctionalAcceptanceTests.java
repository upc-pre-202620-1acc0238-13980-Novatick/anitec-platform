package com.anitec.backend;

import com.anitec.backend.identity.application.*;
import com.anitec.backend.identity.domain.*;
import com.anitec.backend.subscriptions.application.SubscriptionExpirationScheduler;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.*;
import org.springframework.transaction.support.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

/** Real HTTP/PostgreSQL and unchanged business services; security mail uses a controlled in-memory double. */
@Testcontainers @ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "email.delivery.mode=disabled","email.delivery.worker-enabled=false","resend.api-key=",
        "identity.password-recovery.enabled=true","payments.simulation-enabled=true",
        "spring.flyway.placeholders.demo_seed=false","logging.level.root=ERROR"})
class MvpFunctionalAcceptanceTests {
    @Container @ServiceConnection static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void syntheticKey(DynamicPropertyRegistry r){
        String key=EmailCryptoSettingsTests.key();
        r.add("identity.password-recovery.hmac-key",()->key);
    }
    /** Delivery double: captures only after commit, never extracts a code from PostgreSQL. */
    static class Mail implements EmailSender {
        final Map<String,String> verification=new ConcurrentHashMap<>(),recovery=new ConcurrentHashMap<>();
        public boolean send(String to,String subject,String body){return false;}
        public boolean passwordRecoveryConfigured(){return true;}
    }
    static class Intake implements PasswordRecoveryWorkQueue {
        final Queue<Runnable> pending=new ConcurrentLinkedQueue<>();
        public void submit(Runnable work){pending.add(work);}
        void drain(){Runnable work;while((work=pending.poll())!=null)work.run();}
    }
    @TestConfiguration static class Config {
        @Bean @Primary Mail acceptanceMail(){return new Mail();}
        @Bean @Primary Intake acceptanceIntake(){return new Intake();}
        @Bean @Primary SecurityEmailDelivery acceptanceDelivery(Mail mail){return new SecurityEmailDelivery(){
            void committed(Runnable delivery){TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){public void afterCommit(){delivery.run();}});}
            public void verification(Account account){String email=account.getEmail(),code=account.getVerificationCode();committed(()->mail.verification.put(email,code));}
            public void passwordRecovery(UUID account,UUID emission,String recipient,String code,Instant expires){committed(()->mail.recovery.put(recipient,code));}
        };}
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Mail mail;
    @Autowired Intake intake;
    @Autowired SubscriptionExpirationScheduler scheduler;
    @Autowired ScheduledAnnotationBeanPostProcessor scheduledTasks;
    /** Isolate the two reconciliation paths from a wall-clock tick during the scenario. */
    @BeforeEach void disableAutomaticTicks(){scheduledTasks.getScheduledTasks().forEach(task->task.cancel());}
    final HttpClient http=HttpClient.newHttpClient();
    int subscriptionReads;
    static class User {
        UUID id;String email,password,role,token,refresh;
        @Override public String toString(){return "User[REDACTED]";}
    }
    record Response(int status,JsonNode body){JsonNode data(){return body.path("data");}String error(){return body.path("errorCode").asText();}}
    Response call(String method,String path,Object body,User user){return request(method,path,body,user==null?null:user.token);}
    Response request(String method,String path,Object body,String token){
        if(path.equals("/subscription/my-subscription"))subscriptionReads++;
        try{var b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path)).timeout(Duration.ofSeconds(30)).header("Content-Type","application/json");
            if(token!=null)b.header("Authorization","Bearer "+token);
            b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var r=http.send(b.build(),HttpResponse.BodyHandlers.ofString());return new Response(r.statusCode(),r.body().isBlank()?json.createObjectNode():json.readTree(r.body()));
        }catch(Exception e){throw new IllegalStateException("Acceptance HTTP request failed",e);}
    }
    String created(Response r){assertThat(r.status()).isEqualTo(201);return r.data().path("id").asText();}
    User register(String role){var u=new User();u.role=role;u.email=UUID.randomUUID()+"@example.invalid";u.password="Isolated-"+UUID.randomUUID()+"aA1!";
        var r=call("POST","/auth/register",Map.of("name","Test","lastName","Acceptance","email",u.email,"password",u.password,"role",role),null);
        assertThat(r.status()).isEqualTo(201);u.id=UUID.fromString(r.data().path("accountId").asText());
        assertThat(r.data().path("emailDelivery").asText()).isEqualTo("UNCONFIRMED");return u;}
    void verify(User u){String code=mail.verification.get(u.email);assertThat(code!=null&&code.matches("[0-9]{6}")).isTrue();
        assertThat(call("POST","/auth/verify",Map.of("email",u.email,"code",code),null).status()).isEqualTo(200);login(u);}
    void login(User u){var r=call("POST","/auth/login",Map.of("email",u.email,"password",u.password),null);assertThat(r.status()).isEqualTo(200);u.token=r.data().path("accessToken").asText();u.refresh=r.data().path("refreshToken").asText();}
    User user(String role){var u=register(role);verify(u);return u;}
    String farm(User f){return created(call("POST","/farms",Map.of("name","Acceptance farm","location","Synthetic location"),f));}
    Map<String,Object> animalBody(User f,String farm,String code){var species=call("GET","/species",null,f);assertThat(species.status()).isEqualTo(200);
        var body=new HashMap<String,Object>(Map.of("farmId",farm,"speciesId",species.data().get(0).path("id").asText(),"sex","DESCONOCIDO","name","Animal","breed","Test breed","birthDate","2020-01-02","photoUrl","https://example.invalid/animal.jpg"));
        if(code!=null)body.put("code",code);return body;}
    String animal(User f,String farm){return created(call("POST","/animals",animalBody(f,farm,null),f));}
    String invite(User f,User v){var r=call("POST","/invitations",Map.of("vetEmail",v.email),f);assertThat(r.status()).isEqualTo(201);return r.data().path("invitationId").asText();}
    void accept(User v,String invitation){assertThat(call("PUT","/invitations/"+invitation+"/respond",Map.of("action","ACCEPT"),v).status()).isEqualTo(200);}
    String link(User f,User v){accept(v,invite(f,v));var r=call("GET","/vet-links",null,f);assertThat(r.status()).isEqualTo(200);
        for(var l:r.data().path("links"))if(l.path("vetId").asText().equals(v.id.toString()))return l.path("linkId").asText();throw new AssertionError("Missing link");}
    Map<String,Object> attention(String animal,String visit){var b=new HashMap<String,Object>(Map.of("animalId",animal,"title","Clinical evaluation","description","Synthetic clinical record","attentionDate",LocalDate.now(ZoneOffset.UTC).minusDays(1).toString()));if(visit!=null)b.put("visitId",visit);return b;}
    String visit(User v,String animal){return created(call("POST","/visits/schedule",Map.of("animalId",animal,"scheduledAt",Instant.now().plusSeconds(86400).toString(),"reason","Evaluation"),v));}
    JsonNode history(User f,String animal){var r=call("GET","/animals/"+animal+"/medical-history",null,f);assertThat(r.status()).isEqualTo(200);return r.data();}
    JsonNode notices(User u){var r=call("GET","/notifications?size=100",null,u);assertThat(r.status()).isEqualTo(200);return r.data().path("content");}
    void noticeOwnership(User u,JsonNode rows){for(var row:rows)assertThat(jdbc.queryForObject("SELECT user_id FROM notification_notifications WHERE id=?",UUID.class,UUID.fromString(row.path("id").asText()))).isEqualTo(u.id);}
    JsonNode capacity(User u){var r=call("GET",u.role.equals("GANADERO")?"/animals/capacity":"/vet-links/capacity",null,u);assertThat(r.status()).isEqualTo(200);return r.data();}
    void premium(User u){var plans=call("GET","/subscription/plans",null,u);assertThat(plans.status()).isEqualTo(200);String plan=null;
        for(var p:plans.data())if(p.path("type").asText().equals("PREMIUM"))plan=p.path("id").asText();assertThat(plan).isNotNull();
        var checkout=call("POST","/subscription/premium",Map.of("planId",plan),u);assertThat(checkout.status()).isEqualTo(200);
        assertThat(call("POST","/subscription/premium/confirm",Map.of("checkoutId",checkout.data().path("checkoutId").asText(),"outcome","APPROVED"),u).status()).isEqualTo(200);}

    @Test void registeredUsersCompleteClinicalJourneyAndRetainHistoryAfterRevocation(){
        var a=user("GANADERO");var b=user("GANADERO");var v=register("VETERINARIO");
        assertThat(call("POST","/invitations",Map.of("vetEmail",v.email),a).error()).isEqualTo("RECIPIENT_NOT_REGISTERED");verify(v);
        String farm=farm(a);assertThat(call("GET","/farms",null,a).data().size()).isEqualTo(1);
        assertThat(call("GET","/farms/"+farm,null,b).status()).isEqualTo(403);
        String animal=created(call("POST","/animals",animalBody(a,farm,"ACCEPT-1"),a));var before=call("GET","/animals/"+animal,null,a).data();
        var edit=new HashMap<String,Object>();for(String field:List.of("code","speciesId","sex","name","breed","birthDate","photoUrl"))edit.put(field,before.path(field).asText());edit.put("version",before.path("version").asLong());edit.put("name","Updated name");
        assertThat(call("PUT","/animals/"+animal,edit,b).status()).isEqualTo(403);assertThat(call("PUT","/animals/"+animal,edit,a).status()).isEqualTo(200);
        var after=call("GET","/animals/"+animal,null,a).data();assertThat(after.path("name").asText()).isEqualTo("Updated name");
        for(String field:List.of("id","farmId","speciesId","photoUrl","breed","birthDate","sex"))assertThat(after.path(field)).as(field).isEqualTo(before.path(field));
        assertThat(call("POST","/animals/"+animal+"/observations",Map.of("text","Observation by owner"),a).status()).isEqualTo(201);
        assertThat(call("GET","/animals/"+animal+"/observations",null,a).data().get(0).path("text").asText()).isEqualTo("Observation by owner");
        String rejected=invite(a,v);assertThat(call("PUT","/invitations/"+rejected+"/respond",Map.of("action","REJECT"),v).status()).isEqualTo(200);
        assertThat(call("GET","/animals/"+animal,null,v).status()).isEqualTo(403);String link=link(a,v);
        assertThat(call("GET","/animals/"+animal,null,v).status()).isEqualTo(200);assertThat(call("GET","/animals/"+animal,null,b).status()).isEqualTo(403);
        String visit=visit(v,animal);assertThat(call("PATCH","/visits/"+visit+"/status",Map.of("status","COMPLETADA"),v).error()).isEqualTo("ATTENTION_REQUIRED");
        String clinical=created(call("POST","/attentions",attention(animal,visit),v));String date=LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();
        assertThat(call("POST","/attentions/"+clinical+"/treatments",Map.of("description","Treatment","appliedDate",date),v).status()).isEqualTo(200);
        assertThat(call("POST","/attentions/"+clinical+"/vaccinations",Map.of("name","Vaccine","appliedDate",date),v).status()).isEqualTo(200);
        assertThat(call("POST","/attentions/"+clinical+"/instructions",Map.of("content","Care instructions"),v).status()).isEqualTo(201);
        assertThat(call("PUT","/attentions/"+clinical+"/instructions",Map.of("content","Updated instructions"),v).status()).isEqualTo(200);
        String control=created(call("POST","/visits/follow-up",Map.of("attentionId",clinical,"scheduledAt",Instant.now().plusSeconds(172800).toString()),v));
        String controlClinical=created(call("POST","/attentions",attention(animal,control),v));assertThat(controlClinical).isNotEqualTo(clinical);
        assertThat(call("POST","/attentions",attention(animal,control),v).error()).isEqualTo("ATTENTION_ALREADY_EXISTS");
        created(call("POST","/attentions",attention(animal,null),v));var medical=history(a,animal);assertThat(medical).hasSize(3);
        var original=call("GET","/attentions/"+clinical,null,a).data();assertThat(original.path("treatments")).hasSize(1);assertThat(original.path("vaccinations")).hasSize(1);assertThat(original.path("instructions").path("content").asText()).isEqualTo("Updated instructions");
        var farmerNotices=notices(a);var vetNotices=notices(v);noticeOwnership(a,farmerNotices);noticeOwnership(v,vetNotices);assertThat(notices(b)).isEmpty();
        var types=new HashSet<String>();for(var n:farmerNotices)types.add(n.path("type").asText());assertThat(types).contains("VISIT_SCHEDULED","CARE_INSTRUCTIONS_REGISTERED","CARE_INSTRUCTIONS_UPDATED","INVITATION_ANSWERED");
        String notification=farmerNotices.get(0).path("id").asText();assertThat(call("PATCH","/notifications/"+notification+"/read",null,b).status()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT is_read FROM notification_notifications WHERE id=?",Boolean.class,UUID.fromString(notification))).isFalse();
        assertThat(call("PATCH","/notifications/"+notification+"/read",null,a).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT is_read FROM notification_notifications WHERE id=?",Boolean.class,UUID.fromString(notification))).isTrue();
        assertThat(call("DELETE","/vet-links/"+link,null,a).status()).isEqualTo(200);assertThat(call("GET","/visits",null,v).data()).isEmpty();
        assertThat(call("GET","/attentions/"+clinical,null,v).status()).isEqualTo(403);assertThat(call("POST","/attentions",attention(animal,null),v).status()).isEqualTo(403);
        assertThat(history(a,animal)).isEqualTo(medical);assertThat(call("DELETE","/animals/"+animal,null,a).status()).isEqualTo(200);assertThat(history(a,animal)).isEqualTo(medical);
        var reserved=call("POST","/animals",animalBody(a,farm,"ACCEPT-1"),a);assertThat(reserved.status()).isEqualTo(409);assertThat(reserved.error()).isEqualTo("DUPLICATE_ANIMAL_CODE");
    }

    @ParameterizedTest @ValueSource(strings={"GANADERO","VETERINARIO"})
    void passwordRecoveryRevokesOnlyOwnersSessionsAndPreservesDomainAccess(String role){
        var a=user(role);var other=user("GANADERO");String first=a.token,firstRefresh=a.refresh;login(a);String second=a.token;
        assertThat(request("GET","/account/me",null,first).status()).isEqualTo(200);
        UUID operation=UUID.randomUUID();var rotation=call("POST","/auth/refresh-token",Map.of("refreshToken",firstRefresh,"operationId",operation),null);assertThat(rotation.status()).isEqualTo(200);
        assertThat(call("POST","/auth/refresh-token",Map.of("refreshToken",firstRefresh,"operationId",operation),null).data()).isEqualTo(rotation.data());
        assertThat(call("POST","/auth/logout",Map.of("refreshToken",rotation.data().path("refreshToken").asText()),null).status()).isEqualTo(200);
        assertThat(request("GET","/account/me",null,rotation.data().path("accessToken").asText()).status()).isEqualTo(401);assertThat(request("GET","/account/me",null,second).status()).isEqualTo(200);
        String animal;User owner;if(role.equals("GANADERO")){owner=a;animal=animal(a,farm(a));}else{owner=other;animal=animal(other,farm(other));link(other,a);}
        login(a);String third=a.token,thirdRefresh=a.refresh;var prior=call("GET","/animals/"+animal,null,a).data();
        assertThat(call("POST","/auth/password-recovery/request",Map.of("email",a.email),null).status()).isEqualTo(202);intake.drain();String code=mail.recovery.get(a.email);assertThat(code!=null&&code.matches("[0-9]{6}")).isTrue();
        String wrong=code.equals("000000")?"999999":"000000",updated="Updated-"+UUID.randomUUID()+"aA1!";
        assertThat(call("POST","/auth/password-recovery/confirm",Map.of("email",a.email,"code",wrong,"newPassword",updated),null).error()).isEqualTo("RECOVERY_CODE_INVALID");
        assertThat(request("GET","/account/me",null,second).status()).isEqualTo(200);assertThat(request("GET","/account/me",null,third).status()).isEqualTo(200);
        assertThat(call("POST","/auth/password-recovery/confirm",Map.of("email",a.email,"code",code,"newPassword",updated),null).status()).isEqualTo(204);
        for(String token:List.of(first,second,third))assertThat(request("GET","/account/me",null,token).status()).isEqualTo(401);
        assertThat(call("POST","/auth/refresh-token",Map.of("refreshToken",thirdRefresh,"operationId",UUID.randomUUID()),null).status()).isEqualTo(401);
        assertThat(call("GET","/account/me",null,other).status()).isEqualTo(200);
        assertThat(call("POST","/auth/login",Map.of("email",a.email,"password",a.password),null).status()).isEqualTo(401);a.password=updated;login(a);
        assertThat(call("GET","/animals/"+animal,null,a).data()).isEqualTo(prior);assertThat(call("GET","/animals/"+animal,null,owner).status()).isEqualTo(200);
        assertThat(call("POST","/auth/password-recovery/confirm",Map.of("email",a.email,"code",code,"newPassword",updated),null).status()).isEqualTo(400);
    }

    record Excess(User farmer,User vet,String farm,String animal,JsonNode history){}
    Excess excess(){var f=user("GANADERO");var v=user("VETERINARIO");premium(f);premium(v);String farm=farm(f),animal=null;
        for(int n=0;n<16;n++){String id=animal(f,farm);if(n==0)animal=id;}link(f,v);for(int n=0;n<3;n++)link(user("GANADERO"),v);
        created(call("POST","/attentions",attention(animal,null),v));return new Excess(f,v,farm,animal,history(f,animal));}
    void due(Excess s){for(User u:List.of(s.farmer,s.vet))jdbc.update("UPDATE subscriptions_subscriptions SET ends_at=CURRENT_TIMESTAMP-interval '1 second' WHERE account_id=?",u.id);}
    void assertExcessPreserved(Excess s){assertThat(capacity(s.farmer).path("allowedAnimals").asInt()).isEqualTo(15);assertThat(capacity(s.farmer).path("activeAnimals").asInt()).isEqualTo(16);
        assertThat(capacity(s.vet).path("allowedRanchers").asInt()).isEqualTo(3);assertThat(capacity(s.vet).path("activeLinks").asInt()).isEqualTo(4);
        var blocked=call("POST","/animals",animalBody(s.farmer,s.farm,null),s.farmer);assertThat(blocked.status()).isEqualTo(422);assertThat(blocked.error()).isEqualTo("INVENTORY_LIMIT_REACHED");
        String invitation=invite(user("GANADERO"),s.vet);var link=call("PUT","/invitations/"+invitation+"/respond",Map.of("action","ACCEPT"),s.vet);assertThat(link.status()).isEqualTo(422);assertThat(link.error()).isEqualTo("LINKING_LIMIT_REACHED");
        assertThat(history(s.farmer,s.animal)).isEqualTo(s.history);assertThat(history(s.vet,s.animal)).isEqualTo(s.history);
    }
    @Test void premiumExpiryIsReconciledByReadAndPreservesExcessRecords(){var s=excess();due(s);
        for(User u:List.of(s.farmer,s.vet)){var r=call("GET","/subscription/my-subscription",null,u);assertThat(r.status()).isEqualTo(200);assertThat(r.data().path("planType").asText()).isEqualTo("GRATUITO");assertThat(r.data().path("status").asText()).isEqualTo("VENCIDA");assertThat(r.data().path("allowedCapacity").asInt()).isEqualTo(u.role.equals("GANADERO")?15:3);}
        assertExcessPreserved(s);
    }
    @Test void premiumExpiryIsReconciledByScheduledEntryPointWithoutSubscriptionReads()throws Exception{var s=excess();assertThat(subscriptionReads).isZero();due(s);
        assertThat(SubscriptionExpirationScheduler.class.getMethod("expireDueSubscriptions").getAnnotation(Scheduled.class).cron()).isEqualTo("0 */30 * * * *");
        scheduler.expireDueSubscriptions();assertThat(subscriptionReads).isZero();
        for(User u:List.of(s.farmer,s.vet)){var row=jdbc.queryForMap("SELECT plan_type,status,renewal_enabled,revision FROM subscriptions_subscriptions WHERE account_id=?",u.id);
            assertThat(row.get("plan_type")).isEqualTo("GRATUITO");assertThat(row.get("status")).isEqualTo("VENCIDA");assertThat(row.get("renewal_enabled")).isEqualTo(false);assertThat(((Number)row.get("revision")).longValue()).isEqualTo(3);}
        assertExcessPreserved(s);assertThat(subscriptionReads).isZero();
    }
}
