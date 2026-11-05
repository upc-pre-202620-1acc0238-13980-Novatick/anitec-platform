package com.anitec.backend;

import com.anitec.backend.identity.application.*;
import com.anitec.backend.identity.domain.*;
import com.anitec.backend.shared.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class PasswordRecoveryRestartTests {
 @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
 static class Work implements PasswordRecoveryWorkQueue,AutoCloseable {
  final java.util.Queue<Runnable> pending=new ConcurrentLinkedQueue<>();
  public void submit(Runnable r){pending.add(r);}void drain(){Runnable r;while((r=pending.poll())!=null)r.run();}
  public void close(){pending.clear();}
 }
 static class Mail implements EmailSender {
  final Map<String,String> codes=new HashMap<>();
  public boolean send(String to,String subject,String body){return true;}
  public boolean passwordRecoveryConfigured(){return true;}
  public boolean sendPasswordRecovery(UUID id,String to,String subject,String body){var matcher=java.util.regex.Pattern.compile("[0-9]{6}").matcher(body);if(!matcher.find())throw new IllegalStateException("Missing test code");codes.put(to,matcher.group());return true;}
 }
 @TestConfiguration static class Config {
  @Bean @Primary SecurityEmailDelivery testSecurityDelivery(Mail mail,Work queue) { return new SecurityEmailDelivery() {
   public void verification(Account account) {}
   public void passwordRecovery(UUID account,UUID emission,String recipient,String code,java.time.Instant expires) {
    org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
     new org.springframework.transaction.support.TransactionSynchronization() { public void afterCommit() {
      try { queue.submit(()-> { try {mail.sendPasswordRecovery(emission,recipient,"ANITEC - Restablecer contraseña",code);} catch(Exception ignored) {} }); }
      catch(Exception ignored) { /* Delivery failure is not an emission rollback in this isolated double. */ }
     }});
   }
  }; }

  @Bean @Primary Clock recoveryRestartClock(){return Clock.fixed(Instant.parse("2035-01-01T00:00:00Z"),ZoneOffset.UTC);}
  @Bean @Primary Work restartWork(){return new Work();}
  @Bean @Primary Mail restartMail(){return new Mail();}
 }
 String key(){byte[] k=new byte[32];new java.security.SecureRandom().nextBytes(k);return Base64.getEncoder().encodeToString(k);}
 ServletWebServerApplicationContext start(String key,String sessionKey){return (ServletWebServerApplicationContext)new SpringApplication(AnitecApplication.class,Config.class).run(
  "--spring.profiles.active=test","--server.port=0","--spring.main.banner-mode=off","--logging.level.root=ERROR","--logging.level.com.anitec.backend=ERROR",
  "--spring.datasource.url="+postgres.getJdbcUrl(),"--spring.datasource.username="+postgres.getUsername(),"--spring.datasource.password="+postgres.getPassword(),
  "--spring.flyway.placeholders.demo_seed=false","--resend.api-key=","--identity.sessions.recovery-key="+sessionKey,
  "--identity.password-recovery.enabled=true","--identity.password-recovery.hmac-key="+key);}
 String account(ServletWebServerApplicationContext c){String email=UUID.randomUUID()+"@example.invalid";var identity=c.getBean(IdentityService.class);identity.register(new IdentityService.RegisterCommand("Test","Restart",email,"Initial-Password1!",Role.GANADERO));identity.verifyEmail(email,TestVerificationCodes.code(c.getBean(AccountRepository.class).findByEmail(email).orElseThrow()));return email;}
 @Test void codeQuotaAndAttemptsSurviveApplicationRestart(){String hmac=key(),sessionKey=key(),email,code;UUID id;try(var first=start(hmac,sessionKey)){email=account(first);id=first.getBean(AccountRepository.class).findByEmail(email).orElseThrow().getId();var recovery=first.getBean(PasswordRecoveryService.class);recovery.request(email);first.getBean(Work.class).drain();code=first.getBean(Mail.class).codes.get(email);String wrong="000000".equals(code)?"000001":"000000";assertThatThrownBy(()->recovery.confirm(email,wrong,"Updated-Password2!")).isInstanceOf(com.anitec.backend.shared.domain.DomainException.class);}try(var second=start(hmac,sessionKey)){var recovery=second.getBean(PasswordRecoveryService.class);var before=second.getBean(PasswordRecoveryRepository.class).find(id).orElseThrow();assertThat(before.emissions()).isEqualTo(1);assertThat(before.failedAttempts()).isEqualTo(1);recovery.request(email);second.getBean(Work.class).drain();assertThat(second.getBean(Mail.class).codes).isEmpty();recovery.confirm(email,code,"Updated-Password2!");assertThat(second.getBean(IdentityService.class).login(email,"Updated-Password2!").accountId()).isEqualTo(id);}}
 @Test void unprocessedAcceptanceIsNotDurableDeliveryAndDoesNotSpendQuota(){String hmac=key(),sessionKey=key(),email;UUID id;try(var first=start(hmac,sessionKey)){email=account(first);id=first.getBean(AccountRepository.class).findByEmail(email).orElseThrow().getId();first.getBean(PasswordRecoveryService.class).request(email);assertThat(first.getBean(PasswordRecoveryRepository.class).find(id)).isEmpty();assertThat(first.getBean(Mail.class).codes).isEmpty();}try(var second=start(hmac,sessionKey)){assertThat(second.getBean(PasswordRecoveryRepository.class).find(id)).isEmpty();second.getBean(PasswordRecoveryService.class).request(email);second.getBean(Work.class).drain();assertThat(second.getBean(PasswordRecoveryRepository.class).find(id).orElseThrow().emissions()).isEqualTo(1);assertThat(second.getBean(Mail.class).codes.containsKey(email)).isTrue();}}
}
