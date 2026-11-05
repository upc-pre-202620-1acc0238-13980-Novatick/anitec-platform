package com.anitec.backend;

import com.anitec.backend.identity.application.*;
import com.anitec.backend.identity.domain.*;
import com.anitec.backend.identity.infrastructure.*;
import com.anitec.backend.identity.interfaceapi.PasswordRecoveryOriginLimiter;
import com.anitec.backend.shared.domain.DomainException;
import com.anitec.backend.shared.domain.Role;
import com.anitec.backend.shared.infrastructure.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@Testcontainers @ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"identity.password-recovery.enabled=true","identity.password-recovery.origin-limit=10000","resend.api-key="})
class PasswordRecoveryTests {
 @Container @ServiceConnection static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
 @DynamicPropertySource static void key(DynamicPropertyRegistry r){byte[] k=new byte[32];new java.security.SecureRandom().nextBytes(k);String encoded=Base64.getEncoder().encodeToString(k);r.add("identity.password-recovery.hmac-key",()->encoded);}
 static class Time extends Clock {
  final AtomicReference<Instant> time=new AtomicReference<>(Instant.parse("2035-01-01T00:00:00Z"));
  public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return time.get();}
  void advance(long seconds){time.updateAndGet(t->t.plusSeconds(seconds));}
 }
 static class Queue implements PasswordRecoveryWorkQueue {
  final java.util.Queue<Runnable> jobs=new ConcurrentLinkedQueue<>();boolean reject;
  public void submit(Runnable r){if(reject)throw new RejectedExecutionException();jobs.add(r);}
  void drain(){Runnable r;while((r=jobs.poll())!=null)r.run();}
 }
 record Delivery(UUID id,String email,String code) {}
 static class Mail implements EmailSender {
  final List<Delivery> delivered=new CopyOnWriteArrayList<>();boolean accept=true;boolean fail;boolean afterCommit=true;
  public boolean passwordRecoveryConfigured(){return true;}
  public boolean send(String to,String subject,String body){return true;}
  public boolean sendPasswordRecovery(UUID id,String to,String subject,String body){
   afterCommit&=!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
   var match=Pattern.compile("(?<![0-9])[0-9]{6}(?![0-9])").matcher(body);if(!match.find())throw new IllegalStateException("Missing test delivery code");
   delivered.add(new Delivery(id,to,match.group()));if(fail)throw new IllegalStateException("sensitive-provider-payload-"+match.group());return accept;
  }
  List<Delivery> forEmail(String e){return delivered.stream().filter(d->d.email().equals(e)).toList();}
 }
 @TestConfiguration static class Config {
  @Bean @Primary SecurityEmailDelivery testSecurityDelivery(Mail mail,Queue queue) { return new SecurityEmailDelivery() {
   public void verification(Account account) {}
   public void passwordRecovery(UUID account,UUID emission,String recipient,String code,java.time.Instant expires) {
    org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
     new org.springframework.transaction.support.TransactionSynchronization() { public void afterCommit() {
      try { queue.submit(()-> { try {mail.sendPasswordRecovery(emission,recipient,"ANITEC - Restablecer contraseña",code);} catch(Exception ignored) {} }); }
      catch(Exception ignored) { /* Delivery failure is not an emission rollback in this isolated double. */ }
     }});
   }
  }; }

  @Bean @Primary Time recoveryTime(){return new Time();}
  @Bean @Primary Queue recoveryQueue(){return new Queue();}
  @Bean @Primary Mail recoveryMail(){return new Mail();}
 }
 @Autowired Time clock;@Autowired Queue queue;@Autowired Mail mail;
 @Autowired IdentityService identity;@Autowired PasswordRecoveryService service;@Autowired PasswordRecoveryVerifier verifier;
 @Autowired AccountRepository accounts;@Autowired JdbcTemplate jdbc;@Autowired TestRestTemplate http;@Autowired JwtService jwt;
 @SpyBean JdbcPasswordRecoveryRepository recoveries;
 @SpyBean AccountRepositoryImpl accountAdapter;
 @SpyBean PasswordRecoveryCodeGenerator codes;
 @SpyBean PasswordRecoveryOriginLimiter limiter;
 @BeforeEach void prepare(){clock.time.set(Instant.parse("2035-01-01T00:00:00Z"));queue.jobs.clear();queue.reject=false;mail.delivered.clear();mail.accept=true;mail.fail=false;mail.afterCommit=true;}
 String pending(){String e=UUID.randomUUID()+"@example.invalid";identity.register(new IdentityService.RegisterCommand("Test","Recovery",e,"Initial-Password1!",Role.GANADERO));return e;}
 String active(){String e=pending();identity.verifyEmail(e,TestVerificationCodes.code(accounts.findByEmail(e).orElseThrow()));return e;}
 UUID id(String email){return accounts.findByEmail(email).orElseThrow().getId();}
 PasswordRecovery state(String email){return recoveries.find(id(email)).orElseThrow();}
 ResponseEntity<JsonNode> requestOnly(String email){return http.postForEntity("/api/v1/auth/password-recovery/request",Map.of("email",email),JsonNode.class);}
 ResponseEntity<JsonNode> request(String email){var r=requestOnly(email);queue.drain();return r;}
 String issue(String email){assertThat(request(email).getStatusCode().value()).isEqualTo(202);var found=mail.forEmail(Account.normalizeEmail(email));assertThat(found.size()).isPositive();return found.getLast().code();}
 ResponseEntity<JsonNode> confirm(String email,String code,String password){return http.postForEntity("/api/v1/auth/password-recovery/confirm",Map.of("email",email,"code",code,"newPassword",password),JsonNode.class);}
 String wrong(String code){return "000000".equals(code)?"000001":"000000";}
 void invalid(ResponseEntity<JsonNode> r){assertThat(r.getStatusCode().value()).isEqualTo(400);assertThat(r.getBody().path("errorCode").asText()).isEqualTo("RECOVERY_CODE_INVALID");}
 IdentityService.LoginResult login(String e){return identity.login(e,"Initial-Password1!");}
 int access(String token){var h=new HttpHeaders();h.setBearerAuth(token);return http.exchange("/api/v1/account/me",HttpMethod.GET,new HttpEntity<>(h),String.class).getStatusCode().value();}
 int refresh(String token){try(var client=java.net.http.HttpClient.newHttpClient()){var req=java.net.http.HttpRequest.newBuilder(java.net.URI.create(http.getRootUri()+"/api/v1/auth/refresh-token")).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(new ObjectMapper().writeValueAsString(Map.of("refreshToken",token,"operationId",UUID.randomUUID())))).build();return client.send(req,java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();}catch(Exception failure){throw new IllegalStateException("Test refresh failed",failure);}}
 <T> List<T> concurrent(Callable<T> a,Callable<T> b)throws Exception{var barrier=new CyclicBarrier(2);try(var pool=Executors.newFixedThreadPool(2)){var x=pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);return a.call();});var y=pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);return b.call();});return List.of(x.get(30,TimeUnit.SECONDS),y.get(30,TimeUnit.SECONDS));}}
 @Test void eligibleRequestIsGenericAndFirstIssuanceConsumesQuota(){String e=active();var r=request(e);assertThat(r.getStatusCode().value()).isEqualTo(202);assertThat(r.getBody().path("message").asText()).isEqualTo("Si la cuenta puede recuperarse, recibirás un código en el correo indicado");assertThat(r.getBody().has("data")).isFalse();assertThat(state(e).emissions()).isEqualTo(1);assertThat(mail.forEmail(e).size()).isEqualTo(1);}
 @Test void nonexistentPendingAndSuspendedAreNotIssued(){String pending=pending(),suspended=active();jdbc.update("UPDATE identity_accounts SET status='SUSPENDIDA' WHERE id=?",id(suspended));for(String e:List.of(pending,suspended,"absent@example.invalid")){assertThat(request(e).getStatusCode().value()).isEqualTo(202);assertThat(mail.forEmail(e)).isEmpty();}assertThat(recoveries.find(id(pending))).isEmpty();assertThat(recoveries.find(id(suspended))).isEmpty();}
 @Test void nonVerifiedActiveAccountIsNotEligible(){String e=pending();jdbc.update("UPDATE identity_accounts SET status='ACTIVA' WHERE id=?",id(e));assertThat(request(e).getStatusCode().value()).isEqualTo(202);assertThat(recoveries.find(id(e))).isEmpty();}
 @Test void emailIsNormalized(){String e=active();issue("  "+e.toUpperCase(Locale.ROOT)+"  ");assertThat(mail.forEmail(e).size()).isEqualTo(1);}
 @Test void requestResponseDoesNotWaitForAccountLookupOrProvider(){String e=active();assertThat(requestOnly(e).getStatusCode().value()).isEqualTo(202);assertThat(recoveries.find(id(e))).isEmpty();assertThat(mail.forEmail(e)).isEmpty();assertThat(queue.jobs.size()).isEqualTo(1);queue.drain();assertThat(state(e).emissions()).isEqualTo(1);}
 @Test void cooldownDoesNotChangeCodeCountersOrWindow(){String e=active();String c=issue(e);var before=state(e);clock.advance(59);assertThat(request(e).getStatusCode().value()).isEqualTo(202);var after=state(e);assertThat(after.emissionId()).isEqualTo(before.emissionId());assertThat(after.lastIssuedAt()).isEqualTo(before.lastIssuedAt());assertThat(after.windowStartedAt()).isEqualTo(before.windowStartedAt());assertThat(after.emissions()).isEqualTo(1);assertThat(confirm(e,c,"Updated-Password2!").getStatusCode().value()).isEqualTo(204);}
 @Test void exactSixtySecondCooldownAllowsNextIssuance(){String e=active();issue(e);clock.advance(60);issue(e);assertThat(state(e).emissions()).isEqualTo(2);assertThat(mail.forEmail(e).size()).isEqualTo(2);}
 @Test void fifthIssuanceUsableAndSixthRejectedWithoutMutation(){String e=active();String c="";for(int i=0;i<5;i++){if(i>0)clock.advance(60);c=issue(e);}var before=state(e);clock.advance(60);request(e);assertThat(state(e).emissions()).isEqualTo(5);assertThat(state(e).emissionId()).isEqualTo(before.emissionId());assertThat(mail.forEmail(e).size()).isEqualTo(5);assertThat(confirm(e,c,"Updated-Password2!").getStatusCode().value()).isEqualTo(204);}
 @Test void quotaRecoversExactlyAtTwentyFourHours(){String e=active();for(int i=0;i<5;i++){if(i>0)clock.advance(60);issue(e);}clock.time.set(Instant.parse("2035-01-01T23:59:59Z"));request(e);assertThat(state(e).emissions()).isEqualTo(5);clock.advance(1);issue(e);assertThat(state(e).emissions()).isEqualTo(1);assertThat(state(e).windowStartedAt()).isEqualTo(clock.instant());}
 @Test void cooldownSurvivesWindowRollover(){String e=active();issue(e);clock.advance(86400-30);issue(e);var old=state(e);clock.advance(30);request(e);assertThat(state(e).emissionId()).isEqualTo(old.emissionId());clock.advance(30);issue(e);assertThat(state(e).emissions()).isEqualTo(1);assertThat(state(e).windowStartedAt()).isEqualTo(clock.instant());}
 @Test void lastMinuteCodeKeepsFullLifetimeAcrossWindowEnd(){String e=active();issue(e);clock.advance(86400-60);String c=issue(e);clock.advance(300);assertThat(confirm(e,c,"Updated-Password2!").getStatusCode().value()).isEqualTo(204);}
 @Test void newCodeReplacesPrevious(){String e=active();String old=issue(e);clock.advance(60);String current=issue(e);assertThat(old.equals(current)).isFalse();invalid(confirm(e,old,"Updated-Password2!"));assertThat(confirm(e,current,"Updated-Password2!").getStatusCode().value()).isEqualTo(204);}
 @Test void exactTenMinutesExpiresWithoutScheduledCleanup(){String e=active();String c=issue(e);clock.advance(600);invalid(confirm(e,c,"Updated-Password2!"));assertThat(state(e).verifier()).isNull();}
 @Test void codeAtNineMinutesFiftyNineSecondsIsUsable(){String e=active();String c=issue(e);clock.advance(599);assertThat(confirm(e,c,"Updated-Password2!").getStatusCode().value()).isEqualTo(204);}
 @Test void leadingZerosAreTextAndNotRemoved(){String e=active();doReturn("000007").when(codes).generate();String c=issue(e);assertThat(c.equals("000007")).isTrue();assertThat(confirm(e,c,"Updated-Password2!").getStatusCode().value()).isEqualTo(204);}
 @Test void fiveIncorrectAttemptsPersistAndDoNotCloseSessions(){String e=active();var session=login(e);String c=issue(e);for(int n=1;n<=5;n++){invalid(confirm(e,wrong(c),"Updated-Password2!"));assertThat(state(e).failedAttempts()).isEqualTo(n);}assertThat(state(e).verifier()).isNull();invalid(confirm(e,c,"Updated-Password2!"));assertThat(access(session.accessToken())).isEqualTo(200);assertThat(login(e).accountId()).isEqualTo(id(e));}
 @Test void malformedAndUnicodeCodesAreRejected(){String e=active();issue(e);for(String c:List.of("12345","abcdef","１２３４５６"," 12345"))invalid(confirm(e,c,"Updated-Password2!"));assertThat(state(e).failedAttempts()).isZero();}
 @Test void unknownAndNoCodeReturnSameGenericError(){String e=active();var a=confirm(e,"123456","Updated-Password2!");var b=confirm("absent@example.invalid","123456","Updated-Password2!");invalid(a);invalid(b);assertThat(a.getBody().path("message").asText()).isEqualTo(b.getBody().path("message").asText());}
 @Test void pendingCannotSkipRegistrationVerification(){String e=pending();String verification=TestVerificationCodes.code(accounts.findByEmail(e).orElseThrow());request(e);invalid(confirm(e,verification,"Updated-Password2!"));assertThat(accounts.findByEmail(e).orElseThrow().getStatus()).isEqualTo(Account.Status.PENDIENTE);assertThat(recoveries.find(id(e))).isEmpty();}
 @Test void eligibilityRecheckedAtConfirmation(){String e=active();String c=issue(e);jdbc.update("UPDATE identity_accounts SET status='SUSPENDIDA' WHERE id=?",id(e));invalid(confirm(e,c,"Updated-Password2!"));assertThat(accounts.findByEmail(e).orElseThrow().getStatus()).isEqualTo(Account.Status.SUSPENDIDA);}
 @Test void invalidPasswordDoesNotConsumeOrCheckCode(){String e=active();String c=issue(e);for(String password:List.of("short","NoDigits!","no-uppercase1!")){var a=confirm(e,c,password);var b=confirm("absent@example.invalid",wrong(c),password);assertThat(a.getStatusCode().value()).isEqualTo(400);assertThat(a.getBody().path("errorCode").asText()).isEqualTo("VALIDATION_ERROR");assertThat(b.getBody().path("errorCode").asText()).isEqualTo("VALIDATION_ERROR");}assertThat(state(e).failedAttempts()).isZero();assertThat(state(e).consumedAt()).isNull();assertThat(confirm(e,c,"Updated-Password2!").getStatusCode().value()).isEqualTo(204);}
 @Test void successfulResetRequiresNewLoginAndRevokesEverySession(){String e=active();var a=login(e);var b=login(e);String c=issue(e);var result=confirm(e,c,"Updated-Password2!");assertThat(result.getStatusCode().value()).isEqualTo(204);assertThat(result.getBody()).isNull();assertThat(access(a.accessToken())).isEqualTo(401);assertThat(access(b.accessToken())).isEqualTo(401);assertThat(refresh(a.refreshToken())).isEqualTo(401);assertThat(refresh(b.refreshToken())).isEqualTo(401);assertThatThrownBy(()->login(e)).isInstanceOf(DomainException.class);assertThat(identity.login(e,"Updated-Password2!").accountId()).isEqualTo(id(e));assertThat(state(e).verifier()).isNull();assertThat(state(e).consumedAt()).isNotNull();invalid(confirm(e,c,"Updated-Password2!"));}
 @Test void resetDoesNotAffectOtherAccounts(){String a=active(),b=active();var session=login(b);String c=issue(a);assertThat(confirm(a,c,"Updated-Password2!").getStatusCode().value()).isEqualTo(204);assertThat(access(session.accessToken())).isEqualTo(200);assertThat(login(b).accountId()).isEqualTo(id(b));}
 @Test void hmacCannotBeMovedBetweenAccounts(){String a=active(),b=active();String c=issue(a);issue(b);var stateA=state(a);jdbc.update("UPDATE identity_password_recovery SET code_verifier=? WHERE account_id=?",stateA.verifier(),id(b));invalid(confirm(b,c,"Updated-Password2!"));assertThat(login(b).accountId()).isEqualTo(id(b));}
 @Test void hmacBindsIssuanceIdentifier(){String e=active();String c=issue(e);jdbc.update("UPDATE identity_password_recovery SET emission_id=? WHERE account_id=?",UUID.randomUUID(),id(e));invalid(confirm(e,c,"Updated-Password2!"));}
 @Test void verifierIsHmacAndNoPlaintextCodeIsPersisted(){String e=active();String c=issue(e);var s=state(e);assertThat(s.verifier().length).isEqualTo(32);assertThat(java.security.MessageDigest.isEqual(s.verifier(),verifier.sign(id(e),s.emissionId(),c))).isTrue();assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name='identity_password_recovery' AND column_name IN ('code','raw_code','password','new_password')",Integer.class)).isZero();}
 @Test void recoveryDoesNotUseVerificationCounters(){String e=active();var before=accounts.findByEmail(e).orElseThrow();issue(e);var after=accounts.findByEmail(e).orElseThrow();assertThat(after.getVerificationResends()).isEqualTo(before.getVerificationResends());assertThat(after.getVerificationAttempts()).isEqualTo(before.getVerificationAttempts());assertThat(after.getVerificationWindowStartedAt()).isEqualTo(before.getVerificationWindowStartedAt());}
 @Test void twoConfirmationsCannotBothSucceed()throws Exception{String e=active();String c=issue(e);var statuses=concurrent(()->confirm(e,c,"Updated-Password2!").getStatusCode().value(),()->confirm(e,c,"Another-Password3!").getStatusCode().value());assertThat(statuses).containsExactlyInAnyOrder(204,400);assertThat(state(e).consumedAt()).isNotNull();}
 @Test void concurrentWrongAttemptsCannotExceedOrBypassLimit()throws Exception{String e=active();String c=issue(e);for(int n=0;n<3;n++)concurrent(()->confirm(e,wrong(c),"Updated-Password2!").getStatusCode().value(),()->confirm(e,wrong(c),"Updated-Password2!").getStatusCode().value());assertThat(state(e).failedAttempts()).isEqualTo(5);invalid(confirm(e,c,"Updated-Password2!"));}
 @Test void refreshConcurrentWithResetCannotKeepOldSessionActive()throws Exception{String e=active();var session=login(e);String c=issue(e);var results=concurrent(()->confirm(e,c,"Updated-Password2!").getStatusCode().value(),()->refresh(session.refreshToken()));assertThat(results.get(0)).isEqualTo(204);assertThat(results.get(1)).isIn(200,401);assertThat(access(session.accessToken())).isEqualTo(401);assertThat(refresh(session.refreshToken())).isEqualTo(401);}
 @Test void concurrentEmissionRequestsRegisterOnlyOneIssuance()throws Exception{String e=active();concurrent(()->requestOnly(e).getStatusCode().value(),()->requestOnly(e).getStatusCode().value());Runnable a=queue.jobs.poll(),b=queue.jobs.poll();assertThat(a).isNotNull();assertThat(b).isNotNull();concurrent(()->{a.run();return true;},()->{b.run();return true;});queue.drain();assertThat(state(e).emissions()).isEqualTo(1);assertThat(mail.forEmail(e).size()).isEqualTo(1);}
 @Test void rollbackRestoresPasswordCodeAndSessions(){String e=active();var session=login(e);String c=issue(e);String before=accounts.findByEmail(e).orElseThrow().getPasswordHash();doAnswer(call->{call.callRealMethod();throw new IllegalStateException("synthetic failure after password flush");}).when(accountAdapter).save(any(Account.class));try{assertThat(confirm(e,c,"Updated-Password2!").getStatusCode().value()).isEqualTo(500);}finally{reset(accountAdapter);}assertThat(accounts.findByEmail(e).orElseThrow().getPasswordHash().equals(before)).isTrue();assertThat(state(e).consumedAt()).isNull();assertThat(state(e).verifier()).isNotNull();assertThat(access(session.accessToken())).isEqualTo(200);assertThat(confirm(e,c,"Updated-Password2!").getStatusCode().value()).isEqualTo(204);}
 @Test void rolledBackIssuanceNeverSendsMail(){String e=active();doAnswer(call->{call.callRealMethod();throw new IllegalStateException("synthetic issuance failure");}).when(recoveries).save(any());try{assertThat(request(e).getStatusCode().value()).isEqualTo(202);}finally{reset(recoveries);}assertThat(mail.forEmail(e)).isEmpty();assertThat(recoveries.find(id(e))).isEmpty();}
 @Test void providerFailureDoesNotClaimSentOrRefundLogicalEmission(){String e=active();mail.accept=false;var r=request(e);assertThat(r.getStatusCode().value()).isEqualTo(202);assertThat(r.getBody().has("data")).isFalse();assertThat(r.getBody().toString().contains("SENT")).isFalse();assertThat(state(e).emissions()).isEqualTo(1);request(e);assertThat(mail.forEmail(e).size()).isEqualTo(1);}
 @Test void emailIsSentAfterTheIssuanceTransactionCommitted(){String e=active();issue(e);assertThat(mail.afterCommit).isTrue();assertThat(mail.forEmail(e).getLast().id()).isEqualTo(state(e).emissionId());}
 @Test void sensitiveMailFailureIsNotLogged(){String e=active();mail.fail=true;var root=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);var capture=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();capture.start();root.addAppender(capture);try{request(e);String c=mail.forEmail(e).getLast().code();String messages=capture.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).collect(java.util.stream.Collectors.joining("\n"));assertThat(messages.contains(c)||messages.contains(e)||messages.contains("sensitive-provider-payload")||messages.contains("Initial-Password1!")).isFalse();assertThat(capture.list.stream().anyMatch(event->event.getThrowableProxy()!=null)).isFalse();}finally{root.detachAppender(capture);capture.stop();}}
 @Test void publicResponsesAreEquivalentForAllAccountStatesAndLimits(){String eligible=active(),pending=pending(),suspended=active(),limited=active(),cooldown=active();jdbc.update("UPDATE identity_accounts SET status='SUSPENDIDA' WHERE id=?",id(suspended));for(int i=0;i<5;i++){if(i>0)clock.advance(60);issue(limited);}issue(cooldown);List<JsonNode> bodies=new ArrayList<>();for(String e:List.of(eligible,pending,suspended,limited,cooldown,"absent@example.invalid")){var r=requestOnly(e);assertThat(r.getStatusCode().value()).isEqualTo(202);var body=r.getBody().deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)body).remove("timestamp");bodies.add(body);}assertThat(bodies.stream().distinct().count()).isEqualTo(1);}
 @Test void queueSaturationIsGlobalAvailabilityNotAccountInformation(){queue.reject=true;String e=active();for(String address:List.of(e,"absent@example.invalid")){var r=requestOnly(address);assertThat(r.getStatusCode().value()).isEqualTo(503);assertThat(r.getBody().path("errorCode").asText()).isEqualTo("RECOVERY_UNAVAILABLE");}assertThat(recoveries.find(id(e))).isEmpty();}
 @Test void originThrottleReturnsOnlyPublicInformation(){doThrow(new VerificationThrottle("private-code","private-message",clock.instant().plusSeconds(60))).when(limiter).check(anyString(),eq("request"));try{var r=requestOnly("absent@example.invalid");assertThat(r.getStatusCode().value()).isEqualTo(429);assertThat(r.getBody().path("errorCode").asText()).isEqualTo("RECOVERY_ORIGIN_LIMIT");assertThat(r.getBody().has("details")).isFalse();assertThat(r.getBody().toString().contains("private")).isFalse();assertThat(r.getHeaders().getFirst("Retry-After")).isEqualTo("60");}finally{reset(limiter);}}
 @Test void spoofedForwardedHeaderIsNotUsedAsOrigin(){List<String> origins=new CopyOnWriteArrayList<>();doAnswer(call->{origins.add(call.getArgument(0));return call.callRealMethod();}).when(limiter).check(anyString(),anyString());try{var h=new HttpHeaders();h.add("X-Forwarded-For","203.0.113.9");assertThat(http.exchange("/api/v1/auth/password-recovery/request",HttpMethod.POST,new HttpEntity<>(Map.of("email","absent@example.invalid"),h),JsonNode.class).getStatusCode().value()).isEqualTo(202);assertThat(origins).isNotEmpty();assertThat(origins.contains("203.0.113.9")).isFalse();}finally{reset(limiter);}}

 @Test void repeatedGeneratorOutputCannotHangOrConsumeAnotherQuota(){String e=active();doReturn("000007").when(codes).generate();issue(e);clock.advance(60);var before=state(e);request(e);assertThat(state(e).emissions()).isEqualTo(1);assertThat(state(e).emissionId()).isEqualTo(before.emissionId());assertThat(mail.forEmail(e).size()).isEqualTo(1);}
 @Test void verifierContainingSqlFailureIsSanitized(){String e=active();String c=issue(e);doThrow(new org.springframework.dao.DataIntegrityViolationException("sensitive-row-verifier-payload")).when(recoveries).save(any());var root=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);var capture=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();capture.start();root.addAppender(capture);try{var r=confirm(e,wrong(c),"Updated-Password2!");assertThat(r.getStatusCode().value()).isEqualTo(503);assertThat(r.getBody().path("errorCode").asText()).isEqualTo("RECOVERY_UNAVAILABLE");assertThat(capture.list.stream().anyMatch(event->event.getFormattedMessage().contains("sensitive-row")||event.getThrowableProxy()!=null)).isFalse();}finally{root.detachAppender(capture);capture.stop();reset(recoveries);}assertThat(state(e).failedAttempts()).isZero();}

 @Test void veterinarianRecoversWithoutRoleChangesOrSensitiveLogs(){String e=UUID.randomUUID()+"@example.invalid";identity.register(new IdentityService.RegisterCommand("Test","Veterinarian",e,"Initial-Password1!",Role.VETERINARIO));identity.verifyEmail(e,TestVerificationCodes.code(accounts.findByEmail(e).orElseThrow()));var old=login(e);String c=issue(e);var root=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);var capture=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();capture.start();root.addAppender(capture);try{assertThat(confirm(e,c,"Updated-Password2!").getStatusCode().value()).isEqualTo(204);String messages=capture.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).collect(java.util.stream.Collectors.joining("\n"));assertThat(messages.contains(c)||messages.contains("Updated-Password2!")||messages.contains("Initial-Password1!")).isFalse();}finally{root.detachAppender(capture);capture.stop();}assertThat(access(old.accessToken())).isEqualTo(401);assertThat(identity.login(e,"Updated-Password2!").role()).isEqualTo(Role.VETERINARIO);assertThat(accounts.findByEmail(e).orElseThrow().getStatus()).isEqualTo(Account.Status.ACTIVA);}
}
