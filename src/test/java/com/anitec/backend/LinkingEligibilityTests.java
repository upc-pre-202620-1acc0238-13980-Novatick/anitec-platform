package com.anitec.backend;

import com.anitec.backend.identity.domain.AccountRepository;
import com.anitec.backend.identity.infrastructure.ResendEmailSender;
import com.anitec.backend.linking.application.LinkingService;
import com.anitec.backend.shared.domain.DomainException;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
        "spring.flyway.placeholders.demo_seed=false","payments.simulation-enabled=false","logging.level.root=ERROR"})
class LinkingEligibilityTests {
    @Container @ServiceConnection static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    @LocalServerPort int port;
    @Autowired AccountRepository accounts;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired LinkingService linking;
    @SpyBean ResendEmailSender sender;
    final HttpClient http=HttpClient.newHttpClient();
    static class User {UUID id;String email,token;}
    record Response(int status,JsonNode body){JsonNode data(){return body.path("data");}}
    Response call(String method,String path,Object body,User user){
        try{var b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path)).header("Content-Type","application/json");
            if(user!=null)b.header("Authorization","Bearer "+user.token);
            b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var r=http.send(b.build(),HttpResponse.BodyHandlers.ofString());return new Response(r.statusCode(),json.readTree(r.body()));
        }catch(Exception e){throw new IllegalStateException("Eligibility HTTP request failed",e);}
    }
    User user(String role){var u=new User();u.email=UUID.randomUUID()+"@example.invalid";String password="Isolated-"+UUID.randomUUID()+"aA1!";
        assertThat(call("POST","/auth/register",Map.of("name","Test","lastName","Eligibility","email",u.email,"password",password,"role",role),null).status()).isEqualTo(201);
        var account=accounts.findByEmail(u.email).orElseThrow();u.id=account.getId();
        assertThat(call("POST","/auth/verify",Map.of("email",u.email,"code",TestVerificationCodes.code(account)),null).status()).isEqualTo(200);
        var r=call("POST","/auth/login",Map.of("email",u.email,"password",password),null);assertThat(r.status()).isEqualTo(200);u.token=r.data().path("accessToken").asText();return u;}
    Response send(User f,String email){return call("POST","/invitations",Map.of("vetEmail",email),f);}
    String invite(User f,User v){var r=send(f,v.email);assertThat(r.status()).isEqualTo(201);return r.data().path("invitationId").asText();}
    Map<String,Long> counts(){var m=new LinkedHashMap<String,Long>();for(String table:List.of("linking_invitations","linking_links","notification_notifications","identity_email_outbox"))m.put(table,jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class));return m;}
    void state(User v,String status,boolean verified){jdbc.update("UPDATE identity_accounts SET status=?,verified_at=? WHERE id=?",status,verified?java.sql.Timestamp.from(Instant.now()):null,v.id);}

    @ParameterizedTest @CsvSource({"PENDIENTE,false","PENDIENTE,true","ACTIVA,false","SUSPENDIDA,false","SUSPENDIDA,true"})
    void ineligibleRecipientsShareUnknownAccountResponseWithoutWrites(String status,boolean verified){
        var f=user("GANADERO");var v=user("VETERINARIO");state(v,status,verified);clearInvocations(sender);var before=counts();
        var unknown=send(f,UUID.randomUUID()+"@example.invalid");var rejected=send(f,v.email);
        assertThat(rejected.status()).isEqualTo(422);assertThat(unknown.status()).isEqualTo(rejected.status());
        assertThat(rejected.body().path("errorCode").asText()).isEqualTo("RECIPIENT_NOT_REGISTERED");
        assertThat(rejected.body().path("errorCode")).isEqualTo(unknown.body().path("errorCode"));
        assertThat(rejected.body().path("message")).isEqualTo(unknown.body().path("message"));
        assertThat(rejected.body().has("data")).isFalse();assertThat(rejected.body().toString().contains(v.email)).isFalse();
        assertThat(counts()).isEqualTo(before);verify(sender,never()).send(anyString(),anyString(),anyString());
    }

    @Test void wrongRoleAndUnauthenticatedSendAreStillRejected(){var f=user("GANADERO");var other=user("GANADERO");var v=user("VETERINARIO");
        assertThat(send(f,other.email).status()).isEqualTo(422);assertThat(send(v,f.email).status()).isEqualTo(403);
        assertThat(send(null,v.email).status()).isEqualTo(401);}

    @ParameterizedTest @CsvSource({"PENDIENTE,false","ACTIVA,false","SUSPENDIDA,true"})
    void acceptanceRechecksCurrentEligibilityAndRollsBackCapacity(String status,boolean verified){
        var f=user("GANADERO");var v=user("VETERINARIO");String id=invite(f,v);state(v,status,verified);var before=counts();
        int slots=jdbc.queryForObject("SELECT active_links FROM linking_capacity WHERE vet_id=?",Integer.class,v.id);
        // HTTP rejects the revoked/non-usable session before the controller; the service must also defend its boundary.
        assertThat(call("PUT","/invitations/"+id+"/respond",Map.of("action","ACCEPT"),v).status()).isEqualTo(401);
        assertThatThrownBy(()->linking.respond(v.id,UUID.fromString(id),LinkingService.RespondAction.ACCEPT))
                .isInstanceOfSatisfying(DomainException.class,e->{assertThat(e.status().value()).isEqualTo(409);assertThat(e.errorCode()).isEqualTo("INVALID_STATE_TRANSITION");assertThat(e.getMessage()).isEqualTo("No se puede aceptar esta invitación");});
        assertThat(counts()).isEqualTo(before);assertThat(jdbc.queryForObject("SELECT active_links FROM linking_capacity WHERE vet_id=?",Integer.class,v.id)).isEqualTo(slots);
        assertThat(jdbc.queryForObject("SELECT status FROM linking_invitations WHERE id=?",String.class,UUID.fromString(id))).isEqualTo("PENDIENTE");
        state(v,"ACTIVA",true); // A later eligible operation uses current state, not a permanent rejection snapshot.
        linking.respond(v.id,UUID.fromString(id),LinkingService.RespondAction.ACCEPT);
        assertThat(jdbc.queryForObject("SELECT active_links FROM linking_capacity WHERE vet_id=?",Integer.class,v.id)).isEqualTo(slots+1);
    }

    @Test void roleChangeCannotAcceptAndForeignRecipientRemainsForbidden(){var f=user("GANADERO");var v=user("VETERINARIO");var other=user("VETERINARIO");String id=invite(f,v);
        state(v,"SUSPENDIDA",true);
        assertThat(call("PUT","/invitations/"+id+"/respond",Map.of("action","ACCEPT"),other).status()).isEqualTo(403);
        jdbc.update("UPDATE identity_accounts SET status='ACTIVA',role='GANADERO' WHERE id=?",v.id);
        assertThatThrownBy(()->linking.respond(v.id,UUID.fromString(id),LinkingService.RespondAction.ACCEPT)).isInstanceOfSatisfying(DomainException.class,e->assertThat(e.errorCode()).isEqualTo("INVALID_STATE_TRANSITION"));
    }

    @Test void eligibleInvitationRetainsLimitsDuplicatesExpiryAndRevocation(){var v=user("VETERINARIO");var farmers=new ArrayList<User>();
        for(int i=0;i<3;i++){var f=user("GANADERO");farmers.add(f);String id=invite(f,v);
            assertThat(send(f,v.email).status()).isEqualTo(409);
            assertThat(call("PUT","/invitations/"+id+"/respond",Map.of("action","ACCEPT"),v).status()).isEqualTo(200);assertThat(send(f,v.email).status()).isEqualTo(409);}
        var fourth=user("GANADERO");String id=invite(fourth,v);
        var limited=call("PUT","/invitations/"+id+"/respond",Map.of("action","ACCEPT"),v);assertThat(limited.status()).isEqualTo(422);assertThat(limited.body().path("errorCode").asText()).isEqualTo("LINKING_LIMIT_REACHED");
        var f=farmers.get(0);String link=call("GET","/vet-links",null,f).data().path("links").get(0).path("linkId").asText();
        assertThat(call("DELETE","/vet-links/"+link,null,f).status()).isEqualTo(200);
        jdbc.update("UPDATE linking_invitations SET expires_at=now()-interval '1 second' WHERE id=?",UUID.fromString(id));
        var expired=call("PUT","/invitations/"+id+"/respond",Map.of("action","ACCEPT"),v);assertThat(expired.status()).isEqualTo(422);assertThat(expired.body().path("errorCode").asText()).isEqualTo("INVITATION_EXPIRED");
        assertThat(call("GET","/vet-links/capacity",null,v).data().path("activeLinks").asInt()).isEqualTo(2);
    }
}
