package com.anitec.backend;

import com.anitec.backend.identity.application.*;
import com.anitec.backend.identity.infrastructure.*;
import com.anitec.backend.shared.domain.Role;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class EmailOutboxRestartTests {
 @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
 static HttpServer server;static final List<String> received=new CopyOnWriteArrayList<>();
 @TestConfiguration static class Config {
  @Bean @Primary ResendEmailSender restartTransport(Environment e){return new ResendEmailSender("synthetic-test-only","ANITEC <mail@notifications.vitalwatch.space>",e,true,1000,1000,URI.create("http://127.0.0.1:"+server.getAddress().getPort()));}
 }
 ServletWebServerApplicationContext start(String encryption,String session){return (ServletWebServerApplicationContext)new SpringApplication(AnitecApplication.class,Config.class).run(
  "--spring.profiles.active=test","--server.port=0","--spring.main.banner-mode=off","--logging.level.root=ERROR",
  "--spring.datasource.url="+postgres.getJdbcUrl(),"--spring.datasource.username="+postgres.getUsername(),"--spring.datasource.password="+postgres.getPassword(),
  "--spring.flyway.placeholders.demo_seed=false","--identity.sessions.recovery-key="+session,
  "--email.delivery.mode=resend","--email.delivery.encryption-key="+encryption,"--email.delivery.worker-enabled=false",
  "--resend.api-key=synthetic-test-only","--resend.from=ANITEC <mail@notifications.vitalwatch.space>","--identity.password-recovery.enabled=false");}
 @Test void committedEncryptedMessageSurvivesFullApplicationRestartAndRecoveryStaysDisabled()throws Exception{
  String encryption=EmailCryptoSettingsTests.key(),session=EmailCryptoSettingsTests.key();UUID job;String key;
  server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);received.clear();server.createContext("/emails",x->{received.add(new String(x.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));byte[] b=("{\"id\":\""+UUID.randomUUID()+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);x.sendResponseHeaders(200,b.length);x.getResponseBody().write(b);x.close();});server.start();
  try {
   try(var first=start(encryption,session)){first.getBean(IdentityService.class).register(new IdentityService.RegisterCommand("Test","Restart",UUID.randomUUID()+"@example.invalid","Initial-Password1!",Role.GANADERO));var row=first.getBean(JdbcTemplate.class).queryForMap("SELECT id,idempotency_key,state FROM identity_email_outbox");job=(UUID)row.get("id");key=(String)row.get("idempotency_key");assertThat(row.get("state")).isEqualTo("PENDING");assertThat(received).isEmpty();}
   try(var second=start(encryption,session)){var jdbc=second.getBean(JdbcTemplate.class);assertThat(jdbc.queryForObject("SELECT idempotency_key FROM identity_email_outbox WHERE id=?",String.class,job)).isEqualTo(key);assertThat(second.getBean(EmailOutboxWorker.class).runOne()).isTrue();assertThat(jdbc.queryForObject("SELECT state FROM identity_email_outbox WHERE id=?",String.class,job)).isEqualTo("ACCEPTED");assertThat(jdbc.queryForObject("SELECT ciphertext FROM identity_email_outbox WHERE id=?",byte[].class,job)).isNull();assertThat(received).hasSize(1);assertThatThrownBy(()->second.getBean(PasswordRecoveryService.class).requireAvailable()).isInstanceOfSatisfying(com.anitec.backend.shared.domain.DomainException.class,e->assertThat(e.status().value()).isEqualTo(503));}
  }finally{server.stop(0);}
 }
}
