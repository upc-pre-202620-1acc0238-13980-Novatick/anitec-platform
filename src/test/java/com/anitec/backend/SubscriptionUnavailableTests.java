package com.anitec.backend;

import com.anitec.backend.identity.domain.AccountRepository;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers @ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"payments.simulation-enabled=false","email.delivery.mode=disabled","email.delivery.worker-enabled=false","spring.flyway.placeholders.demo_seed=false","identity.password-recovery.enabled=false","logging.level.root=ERROR"})
class SubscriptionUnavailableTests {
    @Container @ServiceConnection static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    @LocalServerPort int port;@Autowired ObjectMapper json;@Autowired AccountRepository accounts;@Autowired JdbcTemplate jdbc;
    record Response(int status,JsonNode body){}
    Response call(String method,String path,Object body,String token)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path)).header("Content-Type","application/json");
        if(token!=null)b.header("Authorization","Bearer "+token);
        b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var r=HttpClient.newHttpClient().send(b.build(),HttpResponse.BodyHandlers.ofString());return new Response(r.statusCode(),json.readTree(r.body()));
    }
    @Test void disabledSimulatorCannotCreateActivateOrRenewButQueriesStayAvailable()throws Exception{
        String email=UUID.randomUUID()+"@example.invalid",password="Isolated-Password1!";
        assertThat(call("POST","/auth/register",Map.of("name","Test","lastName","Disabled","role","GANADERO","email",email,"password",password),null).status()).isEqualTo(201);
        var account=accounts.findByEmail(email).orElseThrow();assertThat(call("POST","/auth/verify",Map.of("email",email,"code",TestVerificationCodes.code(account)),null).status()).isEqualTo(200);
        String token=call("POST","/auth/login",Map.of("email",email,"password",password),null).body().path("data").path("accessToken").asText();
        var plans=call("GET","/subscription/plans",null,token);assertThat(plans.status()).isEqualTo(200);UUID plan=jdbc.queryForObject("SELECT id FROM subscriptions_plans WHERE profile='GANADERO' AND plan_type='PREMIUM'",UUID.class);
        var before=call("GET","/subscription/my-subscription",null,token);long count=jdbc.queryForObject("SELECT count(*) FROM subscriptions_payments",Long.class);
        // A persisted pending operation cannot bypass the environment gate either.
        UUID sub=UUID.fromString(before.body().path("data").path("subscriptionId").asText());String ref="fake_ck_"+UUID.randomUUID();
        jdbc.update("INSERT INTO subscriptions_payments (id,subscription_id,account_id,plan_id,gateway_ref,amount,currency,status) VALUES (?,?,?,?,?,19.90,'PEN','PENDING')",UUID.randomUUID(),sub,account.getId(),plan,ref);
        for(boolean renewal:List.of(false,true)){var r=call("POST","/subscription/premium",Map.of("planId",plan,"renewal",renewal),token);assertThat(r.status()).isEqualTo(503);assertThat(r.body().path("errorCode").asText()).isEqualTo("PAYMENTS_UNAVAILABLE");}
        for(String outcome:List.of("APPROVED","DECLINED")){var r=call("POST","/subscription/premium/confirm",Map.of("checkoutId",ref,"outcome",outcome),token);assertThat(r.status()).isEqualTo(503);assertThat(r.body().path("errorCode").asText()).isEqualTo("PAYMENTS_UNAVAILABLE");}
        assertThat(call("GET","/subscription/my-subscription",null,token).body().path("data")).isEqualTo(before.body().path("data"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscriptions_payments",Long.class)).isEqualTo(count+1);
        assertThat(jdbc.queryForObject("SELECT status FROM subscriptions_payments WHERE gateway_ref=?",String.class,ref)).isEqualTo("PENDING");
    }
}
