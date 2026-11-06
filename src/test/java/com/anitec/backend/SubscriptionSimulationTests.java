package com.anitec.backend;

import com.anitec.backend.identity.domain.AccountRepository;
import com.anitec.backend.shared.application.DomainEventDispatcher;
import com.anitec.backend.subscriptions.application.SubscriptionService;
import com.anitec.backend.subscriptions.domain.Subscription;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@Testcontainers @ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "email.delivery.mode=disabled","email.delivery.worker-enabled=false","identity.password-recovery.enabled=false",
        "spring.flyway.placeholders.demo_seed=false","payments.simulation-enabled=true","logging.level.root=ERROR"})
class SubscriptionSimulationTests {
    @Container @ServiceConnection static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    @LocalServerPort int port;
    @Autowired AccountRepository accounts;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired SubscriptionService subscriptions;
    @SpyBean DomainEventDispatcher events;
    final HttpClient http=HttpClient.newHttpClient();
    record User(UUID id,String token,String role){}
    record Response(int status,JsonNode body){JsonNode data(){return body.path("data");}String error(){return body.path("errorCode").asText();}}
    Response call(String method,String path,Object body,User user){
        try{var b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path)).timeout(Duration.ofSeconds(30)).header("Content-Type","application/json");
            if(user!=null)b.header("Authorization","Bearer "+user.token());
            b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var r=http.send(b.build(),HttpResponse.BodyHandlers.ofString());return new Response(r.statusCode(),json.readTree(r.body()));
        }catch(Exception e){throw new IllegalStateException("Simulation test request failed",e);}
    }
    Response post(String path,Object body,User u){return call("POST",path,body,u);}
    User user(String role){String email=UUID.randomUUID()+"@example.invalid",password="Isolated-"+UUID.randomUUID()+"aA1!";
        assertThat(post("/auth/register",Map.of("name","Test","lastName","Simulation","email",email,"password",password,"role",role),null).status()).isEqualTo(201);
        var account=accounts.findByEmail(email).orElseThrow();
        assertThat(post("/auth/verify",Map.of("email",email,"code",TestVerificationCodes.code(account)),null).status()).isEqualTo(200);
        var login=post("/auth/login",Map.of("email",email,"password",password),null);assertThat(login.status()).isEqualTo(200);
        return new User(account.getId(),login.data().path("accessToken").asText(),role);
    }
    JsonNode state(User u){var r=call("GET","/subscription/my-subscription",null,u);assertThat(r.status()).isEqualTo(200);return r.data();}
    String plan(User u){var r=call("GET","/subscription/plans",null,u);assertThat(r.status()).isEqualTo(200);
        for(var p:r.data())if(p.path("type").asText().equals("PREMIUM"))return p.path("id").asText();throw new AssertionError("Missing premium plan");}
    String checkout(User u,boolean renewal){var r=post("/subscription/premium",Map.of("planId",plan(u),"renewal",renewal),u);assertThat(r.status()).isEqualTo(200);return r.data().path("checkoutId").asText();}
    Response confirm(User u,String checkout,String outcome){return post("/subscription/premium/confirm",Map.of("checkoutId",checkout,"outcome",outcome),u);}
    void approve(User u){assertThat(confirm(u,checkout(u,false),"APPROVED").status()).isEqualTo(200);}
    void due(User u){jdbc.update("UPDATE subscriptions_subscriptions SET ends_at=now()-interval '1 second' WHERE account_id=?",u.id());}
    long payments(User u){return jdbc.queryForObject("SELECT count(*) FROM subscriptions_payments WHERE account_id=?",Long.class,u.id());}
    JsonNode capacity(User u){return call("GET",u.role().equals("GANADERO")?"/animals/capacity":"/vet-links/capacity",null,u).data();}
    void limit(User u,int expected){assertThat(capacity(u).path(u.role().equals("GANADERO")?"allowedAnimals":"allowedRanchers").asInt()).isEqualTo(expected);}
    <T> List<T> concurrent(int n,Callable<T> task)throws Exception{
        var pool=Executors.newFixedThreadPool(n);var start=new CountDownLatch(1);
        try{var futures=IntStream.range(0,n).mapToObj(i->pool.submit(()->{start.await();return task.call();})).toList();start.countDown();
            var result=new ArrayList<T>();for(var f:futures)result.add(f.get(45,TimeUnit.SECONDS));return result;
        }finally{pool.shutdownNow();}
    }

    @ParameterizedTest @ValueSource(strings={"GANADERO","VETERINARIO"})
    void approvedDeclinedRenewedCancelledAndExpiredAreVisibleThroughApi(String role){
        var u=user(role);int free=role.equals("GANADERO")?15:3,premium=role.equals("GANADERO")?150:25;
        var plans=call("GET","/subscription/plans",null,u).data();assertThat(plans.size()).isEqualTo(2);
        for(var p:plans){assertThat(p.path("profile").asText()).isEqualTo(role);assertThat(p.path("currency").asText()).isEqualTo("PEN");
            if(p.path("type").asText().equals("PREMIUM")){assertThat(p.path("price").decimalValue()).isEqualByComparingTo(role.equals("GANADERO")?"19.90":"39.90");assertThat(p.path("capacityLimit").asInt()).isEqualTo(premium);}}
        var initial=state(u);limit(u,free);String rejected=checkout(u,false);assertThat(state(u)).isEqualTo(initial);
        assertThat(confirm(u,rejected,"DECLINED").data().path("paymentStatus").asText()).isEqualTo("DECLINED");
        assertThat(confirm(u,rejected,"APPROVED").data().path("paymentStatus").asText()).isEqualTo("DECLINED");assertThat(state(u)).isEqualTo(initial);
        String first=checkout(u,false);assertThat(confirm(u,first,"APPROVED").status()).isEqualTo(200);var active=state(u);limit(u,premium);
        assertThat(active.path("revision").asLong()).isEqualTo(2);Instant ends=Instant.parse(active.path("endsAt").asText());
        String no=checkout(u,true);assertThat(confirm(u,no,"DECLINED").status()).isEqualTo(200);assertThat(state(u)).isEqualTo(active);
        String renewal=checkout(u,true);assertThat(state(u)).isEqualTo(active);assertThat(confirm(u,renewal,"APPROVED").status()).isEqualTo(200);
        var renewed=state(u);assertThat(Instant.parse(renewed.path("endsAt").asText())).isEqualTo(ends.atOffset(ZoneOffset.UTC).plusMonths(1).toInstant());assertThat(renewed.path("revision").asLong()).isEqualTo(3);
        assertThat(confirm(u,renewal,"DECLINED").data().path("paymentStatus").asText()).isEqualTo("APPROVED");assertThat(state(u)).isEqualTo(renewed);
        String pending=checkout(u,true);var cancelled=post("/subscription/cancel-renewal",null,u);assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.data().path("endsAt")).isEqualTo(renewed.path("endsAt"));assertThat(cancelled.data().path("renewalEnabled").asBoolean()).isFalse();limit(u,premium);
        assertThat(confirm(u,pending,"APPROVED").data().path("paymentStatus").asText()).isEqualTo("DECLINED");
        assertThat(post("/subscription/premium",Map.of("planId",plan(u),"renewal",true),u).status()).isEqualTo(409);
        assertThat(post("/subscription/cancel-renewal",null,u).data()).isEqualTo(cancelled.data());
        due(u);subscriptions.expireDueSubscriptions();var expired=state(u);assertThat(expired.path("planType").asText()).isEqualTo("GRATUITO");assertThat(expired.path("status").asText()).isEqualTo("VENCIDA");limit(u,free);
        assertThat(confirm(u,first,"APPROVED").data().path("planType").asText()).isEqualTo("GRATUITO");assertThat(state(u)).isEqualTo(expired);
        approve(u);limit(u,premium);
    }

    @Test void concurrentInitializationsAndConfirmationsHaveOneEffect()throws Exception{
        var u=user("GANADERO");String plan=plan(u);
        var starts=concurrent(8,()->post("/subscription/premium",Map.of("planId",plan),u));
        assertThat(starts).allSatisfy(r->assertThat(r.status()).isEqualTo(200));
        var refs=starts.stream().map(r->r.data().path("checkoutId").asText()).distinct().toList();assertThat(refs).hasSize(1);assertThat(payments(u)).isEqualTo(1);
        var results=concurrent(8,()->confirm(u,refs.getFirst(),"APPROVED"));assertThat(results).allSatisfy(r->assertThat(r.status()).isEqualTo(200));
        assertThat(state(u).path("revision").asLong()).isEqualTo(2);limit(u,150);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_notifications WHERE user_id=? AND type='PREMIUM_ACTIVATED'",Long.class,u.id())).isEqualTo(1);
    }

    @Test void conflictingConcurrentOutcomesCannotRewriteTheWinner()throws Exception{
        var u=user("VETERINARIO");String ref=checkout(u,false);var index=new java.util.concurrent.atomic.AtomicInteger();
        var results=concurrent(8,()->confirm(u,ref,index.getAndIncrement()%2==0?"APPROVED":"DECLINED"));
        assertThat(results).allSatisfy(r->assertThat(r.status()).isEqualTo(200));
        var statuses=results.stream().map(r->r.data().path("paymentStatus").asText()).distinct().toList();assertThat(statuses).hasSize(1);
        boolean approved=statuses.getFirst().equals("APPROVED");assertThat(state(u).path("revision").asLong()).isEqualTo(approved?2:1);limit(u,approved?25:3);
    }

    @Test void concurrentRenewalReplaysCannotAccreditMultiplePeriods()throws Exception{
        var u=user("GANADERO");approve(u);var before=state(u);String ref=checkout(u,true);
        assertThat(concurrent(8,()->confirm(u,ref,"APPROVED"))).allSatisfy(r->assertThat(r.status()).isEqualTo(200));
        assertThat(Instant.parse(state(u).path("endsAt").asText())).isEqualTo(Instant.parse(before.path("endsAt").asText()).atOffset(ZoneOffset.UTC).plusMonths(1).toInstant());assertThat(state(u).path("revision").asLong()).isEqualTo(3);
    }

    @Test void cancellationAndConfirmationSerializeWithoutLosingCancellation()throws Exception{
        var u=user("GANADERO");approve(u);Instant original=Instant.parse(state(u).path("endsAt").asText());String ref=checkout(u,true);
        var i=new java.util.concurrent.atomic.AtomicInteger();var results=concurrent(2,()->i.getAndIncrement()==0?post("/subscription/cancel-renewal",null,u):confirm(u,ref,"APPROVED"));
        assertThat(results).allSatisfy(r->assertThat(r.status()).isEqualTo(200));var state=state(u);assertThat(state.path("renewalEnabled").asBoolean()).isFalse();
        var payment=confirm(u,ref,"APPROVED").data();boolean credited=payment.path("paymentStatus").asText().equals("APPROVED");
        assertThat(Instant.parse(state.path("endsAt").asText())).isEqualTo(credited?original.atOffset(ZoneOffset.UTC).plusMonths(1).toInstant():original);
    }

    @Test void sweepRevalidatesDueStateAndNeverGrantsAnUnpaidPeriod()throws Exception{
        var u=user("VETERINARIO");approve(u);String ref=checkout(u,true);due(u);
        assertThat(concurrent(2,()->subscriptions.expireDueSubscriptions())).allSatisfy(n->assertThat(n).isGreaterThanOrEqualTo(0));
        assertThat(state(u).path("revision").asLong()).isEqualTo(3);limit(u,3);
        assertThat(confirm(u,ref,"APPROVED").status()).isEqualTo(200);assertThat(state(u).path("planType").asText()).isEqualTo("PREMIUM");limit(u,25);
    }

    @Test void unCancelledPremiumExpiresAndReadReconcilesOverdueState(){
        var u=user("GANADERO");approve(u);assertThat(state(u).path("renewalEnabled").asBoolean()).isTrue();due(u);
        assertThat(state(u).path("planType").asText()).isEqualTo("GRATUITO");limit(u,15);long revision=state(u).path("revision").asLong();
        subscriptions.expireDueSubscriptions();assertThat(state(u).path("revision").asLong()).isEqualTo(revision);
    }

    @Test void ownershipRoleValidationAndServerAmountsCannotBeBypassed(){
        var farmer=user("GANADERO");var other=user("GANADERO");var vet=user("VETERINARIO");String ref=checkout(farmer,false);
        var before=state(other);assertThat(confirm(other,ref,"APPROVED").status()).isEqualTo(403);assertThat(state(other)).isEqualTo(before);
        assertThat(confirm(vet,ref,"APPROVED").status()).isEqualTo(403);
        assertThat(post("/subscription/premium",Map.of("planId",plan(vet)),farmer).error()).isEqualTo("PLAN_PROFILE_MISMATCH");
        assertThat(post("/subscription/premium/confirm",Map.of("checkoutId",ref,"outcome","APPROVED"),null).status()).isEqualTo(401);
        assertThat(post("/subscription/premium",Map.of(),farmer).status()).isEqualTo(400);
        assertThat(post("/subscription/premium/confirm",Map.of("checkoutId",ref,"outcome","OTHER"),farmer).status()).isEqualTo(400);
        assertThat(post("/subscription/premium",Map.of("planId",plan(farmer),"amount",0,"capacityLimit",99999,"accountId",other.id()),farmer).data().path("amount").decimalValue()).isEqualByComparingTo("19.90");
        assertThat(confirm(farmer,ref,"APPROVED").status()).isEqualTo(200);limit(farmer,150);limit(other,15);
    }

    @Test void inactivePlansCannotStartOrActivateASimulation(){
        var u=user("GANADERO");String plan=plan(u),ref=checkout(u,false);
        try{jdbc.update("UPDATE subscriptions_plans SET active=false WHERE id=?",UUID.fromString(plan));
            assertThat(post("/subscription/premium",Map.of("planId",plan),u).error()).isEqualTo("PLAN_UNAVAILABLE");
            assertThat(confirm(u,ref,"APPROVED").error()).isEqualTo("PLAN_UNAVAILABLE");assertThat(state(u).path("planType").asText()).isEqualTo("GRATUITO");
        }finally{jdbc.update("UPDATE subscriptions_plans SET active=true WHERE id=?",UUID.fromString(plan));}
    }

    @Test void failedCapacityPropagationRollsBackApprovalAndAllowsSafeRetry(){
        var u=user("GANADERO");String ref=checkout(u,false);
        doThrow(new IllegalStateException("Synthetic rollback")).when(events).publish(any(Subscription.PremiumActivated.class));
        try{assertThat(confirm(u,ref,"APPROVED").status()).isEqualTo(500);}finally{reset(events);}
        assertThat(state(u).path("planType").asText()).isEqualTo("GRATUITO");limit(u,15);
        assertThat(jdbc.queryForObject("SELECT status FROM subscriptions_payments WHERE gateway_ref=?",String.class,ref)).isEqualTo("PENDING");
        assertThat(confirm(u,ref,"APPROVED").status()).isEqualTo(200);limit(u,150);
    }

    String farm(User u){var r=post("/farms",Map.of("name","Lab","location","Synthetic"),u);assertThat(r.status()).isEqualTo(201);return r.data().path("id").asText();}
    Response animal(User u,String farm){return post("/animals",Map.of("farmId",farm,"speciesId",jdbc.queryForObject("SELECT id FROM livestock_species LIMIT 1",UUID.class),"sex","DESCONOCIDO"),u);}
    String link(User f,User v){String email=accounts.findById(v.id()).orElseThrow().getEmail();var r=post("/invitations",Map.of("vetEmail",email),f);assertThat(r.status()).isEqualTo(201);
        return r.data().path("invitationId").asText();}
    Response accept(User v,String ref){return call("PUT","/invitations/"+ref+"/respond",Map.of("action","ACCEPT"),v);}

    @Test void downgradePreservesExcessAnimalsLinksAndClinicalHistory(){
        var f=user("GANADERO");var v=user("VETERINARIO");approve(f);approve(v);String farm=farm(f);String animal=null;
        for(int n=0;n<16;n++){var r=animal(f,farm);assertThat(r.status()).isEqualTo(201);if(n==0)animal=r.data().path("id").asText();}
        assertThat(accept(v,link(f,v)).status()).isEqualTo(200);
        for(int n=0;n<3;n++)assertThat(accept(v,link(user("GANADERO"),v)).status()).isEqualTo(200);
        var attention=post("/attentions",Map.of("animalId",animal,"title","Control","description","Synthetic record","attentionDate",LocalDate.now().toString()),v);
        assertThat(attention.status()).isEqualTo(201);String id=attention.data().path("id").asText();
        due(f);due(v);subscriptions.expireDueSubscriptions();limit(f,15);limit(v,3);
        assertThat(capacity(f).path("activeAnimals").asInt()).isEqualTo(16);assertThat(capacity(v).path("activeLinks").asInt()).isEqualTo(4);
        assertThat(animal(f,farm).error()).isEqualTo("INVENTORY_LIMIT_REACHED");
        assertThat(accept(v,link(user("GANADERO"),v)).error()).isEqualTo("LINKING_LIMIT_REACHED");
        assertThat(call("GET","/animals/"+animal,null,f).status()).isEqualTo(200);
        assertThat(call("GET","/attentions/"+id,null,v).status()).isEqualTo(200);
        assertThat(call("GET","/attentions/"+id,null,f).status()).isEqualTo(200);
        var stranger=user("GANADERO");approve(stranger);assertThat(call("GET","/animals/"+animal,null,stranger).status()).isEqualTo(403);
    }
}
