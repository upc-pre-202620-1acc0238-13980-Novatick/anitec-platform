package com.anitec.backend;

import com.anitec.backend.identity.application.IdentityService;
import com.anitec.backend.identity.domain.AccountRepository;
import com.anitec.backend.shared.domain.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import java.net.*;
import java.net.http.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class SessionRestartTests {
 @org.springframework.boot.test.context.TestConfiguration static class RestartClockConfiguration {
   @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
   java.time.Clock restartClock(){return java.time.Clock.fixed(java.time.Instant.parse("2030-01-01T00:00:00Z"),java.time.ZoneOffset.UTC);}
 }
 @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
 String randomKey(){byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);return Base64.getEncoder().encodeToString(key);}
 ServletWebServerApplicationContext start(String recoveryKey,String jwtKey){return (ServletWebServerApplicationContext)new SpringApplication(AnitecApplication.class,RestartClockConfiguration.class).run(
   "--spring.profiles.active=test","--server.port=0","--spring.main.banner-mode=off","--logging.level.root=ERROR",
   "--logging.level.com.anitec.backend=ERROR","--spring.datasource.url="+postgres.getJdbcUrl(),
   "--spring.datasource.username="+postgres.getUsername(),"--spring.datasource.password="+postgres.getPassword(),
   "--spring.flyway.placeholders.demo_seed=false","--resend.api-key=","--payments.simulation-enabled=false",
   "--jwt.secret="+jwtKey,"--identity.sessions.recovery-key="+recoveryKey);}
 int access(ServletWebServerApplicationContext node,String token) throws Exception{return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+node.getWebServer().getPort()+"/api/v1/account/me")).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode();}
 @Test void sessionsRecoveryAndRevocationsSurviveApplicationRestarts() throws Exception {
   String key=randomKey(),jwtKey=randomKey()+randomKey();UUID op=UUID.randomUUID();
   IdentityService.LoginResult login;IdentityService.TokenPair result;
   try(var first=start(key,jwtKey)){
     var identity=first.getBean(IdentityService.class);String email=UUID.randomUUID()+"@example.invalid";
     identity.register(new IdentityService.RegisterCommand("Test","Restart",email,"Test-Password1!",Role.GANADERO));
     identity.verifyEmail(email,TestVerificationCodes.code(first.getBean(AccountRepository.class).findByEmail(email).orElseThrow()));
     login=identity.login(email,"Test-Password1!");result=identity.refresh(login.refreshToken(),op);
   }
   try(var second=start(key,jwtKey)){
     assertThat(access(second,result.accessToken())).isEqualTo(200);
     assertThat(second.getBean(IdentityService.class).refresh(login.refreshToken(),op).equals(result)).isTrue();
     second.getBean(IdentityService.class).logout(result.refreshToken());
   }
   try(var third=start(key,jwtKey)){
     assertThat(access(third,result.accessToken())).isEqualTo(401);
     assertThatThrownBy(()->third.getBean(IdentityService.class).refresh(result.refreshToken(),UUID.randomUUID())).isInstanceOf(com.anitec.backend.shared.domain.DomainException.class);
   }
 }
}
