package com.anitec.backend;

import com.anitec.backend.identity.application.*;
import com.anitec.backend.identity.domain.*;
import com.anitec.backend.identity.infrastructure.*;
import com.anitec.backend.shared.domain.*;
import com.anitec.backend.shared.domain.Role;
import com.anitec.backend.shared.infrastructure.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@Testcontainers @ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"resend.api-key=","payments.simulation-enabled=false"})
class SessionSecurityTests {
 @Container @ServiceConnection static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
 @TestConfiguration static class TimeConfig { @Bean @Primary MutableClock controlledClock(){return new MutableClock();} }
 static class MutableClock extends Clock {
   final AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2030-01-01T00:00:00Z"));
   public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;} public Instant instant(){return now.get();}
   void advance(long seconds){now.updateAndGet(i->i.plusSeconds(seconds));}
 }
 @Autowired MutableClock clock;
 @Autowired IdentityService identity;
 @Autowired SessionService sessions;
 @Autowired AccountRepository accounts;
 @Autowired JdbcTemplate jdbc;
 @Autowired TestRestTemplate http;
 @Autowired JwtService jwt;
 @SpyBean SessionCryptography crypto;
 @SpyBean JdbcSessionRepository repository;
 @BeforeEach void resetClock(){clock.now.set(Instant.parse("2030-01-01T00:00:00Z"));}
 IdentityService.LoginResult login(){return login(Role.GANADERO);}
 IdentityService.LoginResult login(Role role){
   String email=UUID.randomUUID()+"@example.invalid";
   identity.register(new IdentityService.RegisterCommand("Test","Session",email,"Test-Password1!",role));
   identity.verifyEmail(email,accounts.findByEmail(email).orElseThrow().getVerificationCode());
   return identity.login(email,"Test-Password1!");
 }
 UUID sid(String access){return UUID.fromString(jwt.parse(access).get("sid",String.class));}
 ResponseEntity<JsonNode> access(String token){return access("/api/v1/account/me",token);}
 ResponseEntity<JsonNode> access(String path,String token){
   var headers=new HttpHeaders();headers.setBearerAuth(token);
   return http.exchange(path,HttpMethod.GET,new HttpEntity<>(headers),JsonNode.class);
 }
 ResponseEntity<JsonNode> refresh(String token,UUID op){
   try {
     var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
     var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(http.getRootUri()+"/api/v1/auth/refresh-token"))
       .header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("refreshToken",token,"operationId",op)))).build();
     var result=java.net.http.HttpClient.newHttpClient().send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
     return ResponseEntity.status(result.statusCode()).body(mapper.readTree(result.body()));
   } catch(Exception failure){throw new IllegalStateException("Test HTTP request failed",failure);}
 }
 ResponseEntity<JsonNode> logout(String token){return http.postForEntity("/api/v1/auth/logout",Map.of("refreshToken",token),JsonNode.class);}
 void invalid(Runnable operation){assertThatThrownBy(operation::run).isInstanceOfSatisfying(DomainException.class,e->assertThat(e.status().value()).isEqualTo(401));}
 List<ResponseEntity<JsonNode>> concurrent(Callable<ResponseEntity<JsonNode>> a,Callable<ResponseEntity<JsonNode>> b) throws Exception {
   var start=new CyclicBarrier(2);
   try(var executor=Executors.newFixedThreadPool(2)){
     var x=executor.submit(()->{start.await(10,TimeUnit.SECONDS);return a.call();});
     var y=executor.submit(()->{start.await(10,TimeUnit.SECONDS);return b.call();});
     return List.of(x.get(20,TimeUnit.SECONDS),y.get(20,TimeUnit.SECONDS));
   }
 }
 @Test void loginCreatesRecognizedSession(){var a=login();assertThat(access(a.accessToken()).getStatusCode().value()).isEqualTo(200);assertThat(repository.lockSession(sid(a.accessToken())).accountId()).isEqualTo(a.accountId());}
 @Test void twoLoginsRemainIndependent(){var a=login();var b=identity.login(a.email(),"Test-Password1!");assertThat(sid(a.accessToken())).isNotEqualTo(sid(b.accessToken()));assertThat(access(a.accessToken()).getStatusCode().value()).isEqualTo(200);}
 @Test void logoutOnlyCurrentSession(){var a=login();var b=identity.login(a.email(),"Test-Password1!");assertThat(logout(a.refreshToken()).getStatusCode().value()).isEqualTo(200);assertThat(access(a.accessToken()).getStatusCode().value()).isEqualTo(401);assertThat(access(b.accessToken()).getStatusCode().value()).isEqualTo(200);invalid(()->identity.refresh(a.refreshToken(),UUID.randomUUID()));}
 @Test void repeatedLogoutIsIdempotent(){var a=login();assertThat(logout(a.refreshToken()).getStatusCode().value()).isEqualTo(200);assertThat(logout(a.refreshToken()).getStatusCode().value()).isEqualTo(200);assertThat(access(a.accessToken()).getStatusCode().value()).isEqualTo(401);}
 @Test void logoutWorksWithExpiredAccessAndValidRefresh(){var a=login();clock.advance(86401);var headers=new HttpHeaders();headers.setBearerAuth(a.accessToken());var result=http.exchange("/api/v1/auth/logout",HttpMethod.POST,new HttpEntity<>(Map.of("refreshToken",a.refreshToken()),headers),JsonNode.class);assertThat(result.getStatusCode().value()).isEqualTo(200);invalid(()->identity.refresh(a.refreshToken(),UUID.randomUUID()));}
 @Test void sessionIdIsNotALogoutCredential(){var a=login();logout(sid(a.accessToken()).toString());assertThat(access(a.accessToken()).getStatusCode().value()).isEqualTo(200);}
 @Test void unknownLogoutDoesNotRevealSessions(){assertThat(logout("unknown-credential").getStatusCode().value()).isEqualTo(200);}
 @Test void refreshedPairUsesSameSession(){var a=login();var b=identity.refresh(a.refreshToken(),UUID.randomUUID());assertThat(sid(b.accessToken())).isEqualTo(sid(a.accessToken()));assertThat(access(b.accessToken()).getStatusCode().value()).isEqualTo(200);}
 @Test void identicalRetryReturnsExactlySamePair(){var a=login();UUID op=UUID.randomUUID();var b=identity.refresh(a.refreshToken(),op);clock.advance(59);var c=identity.refresh(a.refreshToken(),op);assertThat(b.equals(c)).isTrue();assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_session_tokens WHERE session_id=?",Integer.class,sid(b.accessToken()))).isEqualTo(2);}
 @Test void exactSixtySecondsIsOutsideTolerance(){var a=login();UUID op=UUID.randomUUID();var b=identity.refresh(a.refreshToken(),op);clock.advance(60);invalid(()->identity.refresh(a.refreshToken(),op));assertThat(access(b.accessToken()).getStatusCode().value()).isEqualTo(401);}
 @Test void retryAfterToleranceRevokesSession(){var a=login();UUID op=UUID.randomUUID();identity.refresh(a.refreshToken(),op);clock.advance(61);invalid(()->identity.refresh(a.refreshToken(),op));}
 @Test void retryAfterSuccessorConsumptionRevokesChain(){var a=login();UUID op=UUID.randomUUID();var b=identity.refresh(a.refreshToken(),op);var c=identity.refresh(b.refreshToken(),UUID.randomUUID());invalid(()->identity.refresh(a.refreshToken(),op));assertThat(access(c.accessToken()).getStatusCode().value()).isEqualTo(401);}
 @Test void replayWithAnotherOperationRevokesOnlyAffectedSession(){var a=login();var other=identity.login(a.email(),"Test-Password1!");var b=identity.refresh(a.refreshToken(),UUID.randomUUID());invalid(()->identity.refresh(a.refreshToken(),UUID.randomUUID()));assertThat(access(b.accessToken()).getStatusCode().value()).isEqualTo(401);assertThat(access(other.accessToken()).getStatusCode().value()).isEqualTo(200);}
 @Test void revokedSessionCannotRecoverResult(){var a=login();UUID op=UUID.randomUUID();var b=identity.refresh(a.refreshToken(),op);logout(b.refreshToken());invalid(()->identity.refresh(a.refreshToken(),op));assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_session_tokens WHERE session_id=? AND recovery_ciphertext IS NOT NULL",Integer.class,sid(a.accessToken()))).isZero();}
 @Test void sameOperationConcurrentGetsOneSuccessor() throws Exception {var a=login();UUID op=UUID.randomUUID();var results=concurrent(()->refresh(a.refreshToken(),op),()->refresh(a.refreshToken(),op));assertThat(results.stream().map(r->r.getStatusCode().value()).toList()).containsExactly(200,200);assertThat(results.get(0).getBody().path("data").equals(results.get(1).getBody().path("data"))).isTrue();assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_session_tokens WHERE session_id=?",Integer.class,sid(a.accessToken()))).isEqualTo(2);}
 @Test void differentOperationsConcurrentCannotProduceTwoSuccessors() throws Exception {var a=login();var results=concurrent(()->refresh(a.refreshToken(),UUID.randomUUID()),()->refresh(a.refreshToken(),UUID.randomUUID()));assertThat(results.stream().map(r->r.getStatusCode().value()).toList()).containsExactlyInAnyOrder(200,401);assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_session_tokens WHERE session_id=?",Integer.class,sid(a.accessToken()))).isEqualTo(2);assertThat(access(a.accessToken()).getStatusCode().value()).isEqualTo(401);}
 @Test void refreshAgainstLogoutNeverResurrectsSession() throws Exception {var a=login();var results=concurrent(()->refresh(a.refreshToken(),UUID.randomUUID()),()->logout(a.refreshToken()));assertThat(results.stream().map(r->r.getStatusCode().value()).toList()).allMatch(s->s==200||s==401);assertThat(access(a.accessToken()).getStatusCode().value()).isEqualTo(401);invalid(()->identity.refresh(a.refreshToken(),UUID.randomUUID()));}
 @Test void concurrentLogoutsAreIdempotent() throws Exception {var a=login();assertThat(concurrent(()->logout(a.refreshToken()),()->logout(a.refreshToken())).stream().map(r->r.getStatusCode().value()).toList()).containsExactly(200,200);}
 @Test void refreshAgainstGlobalRevocationNeverResurrects() throws Exception {var a=login();concurrent(()->refresh(a.refreshToken(),UUID.randomUUID()),()->{sessions.revokeAll(a.accountId());return ResponseEntity.ok().build();});assertThat(access(a.accessToken()).getStatusCode().value()).isEqualTo(401);}
 @Test void globalRevocationClosesAllSessions(){var a=login();var b=identity.login(a.email(),"Test-Password1!");sessions.revokeAll(a.accountId());assertThat(access(a.accessToken()).getStatusCode().value()).isEqualTo(401);assertThat(access(b.accessToken()).getStatusCode().value()).isEqualTo(401);}
 @Test void absoluteSevenDayLimitCannotBeExtended(){var a=login();clock.advance(6*86400);var b=identity.refresh(a.refreshToken(),UUID.randomUUID());clock.advance(86400);assertThat(access(b.accessToken()).getStatusCode().value()).isEqualTo(401);invalid(()->identity.refresh(b.refreshToken(),UUID.randomUUID()));}
 @Test void nearExpiryBothTokensAreCapped(){var a=login();clock.advance(7*86400-30);var b=identity.refresh(a.refreshToken(),UUID.randomUUID());assertThat(b.expiresIn()).isEqualTo(30);assertThat(jwt.parse(b.accessToken()).getExpiration().toInstant()).isEqualTo(clock.instant().plusSeconds(30));assertThat(repository.token(RefreshToken.digest(b.refreshToken())).orElseThrow().expiresAt()).isEqualTo(clock.instant().plusSeconds(30));clock.advance(30);assertThat(access(b.accessToken()).getStatusCode().value()).isEqualTo(401);}
 @Test void expiredAccessHasStableCode(){var a=login();clock.advance(86400);var r=access(a.accessToken());assertThat(r.getStatusCode().value()).isEqualTo(401);assertThat(r.getBody().path("errorCode").asText()).isEqualTo("ACCESS_TOKEN_EXPIRED");assertThat(refresh(a.refreshToken(),UUID.randomUUID()).getStatusCode().value()).isEqualTo(200);}
 @Test void passwordUpdateRevokesPersistently(){var a=login();jdbc.update("UPDATE identity_accounts SET password_hash=? WHERE id=?","changed-synthetic-hash",a.accountId());assertThat(access(a.accessToken()).getStatusCode().value()).isEqualTo(401);invalid(()->identity.refresh(a.refreshToken(),UUID.randomUUID()));}
 @Test void suspensionAndReactivationDoNotReviveSessions(){var a=login();jdbc.update("UPDATE identity_accounts SET status='SUSPENDIDA' WHERE id=?",a.accountId());assertThat(access(a.accessToken()).getStatusCode().value()).isEqualTo(401);jdbc.update("UPDATE identity_accounts SET status='ACTIVA' WHERE id=?",a.accountId());assertThat(access(a.accessToken()).getStatusCode().value()).isEqualTo(401);}
 @Test void failedLoginDoesNotRevokeOtherSessions(){var a=login();invalid(()->identity.login(a.email(),"Wrong-Password1!"));assertThat(access(a.accessToken()).getStatusCode().value()).isEqualTo(200);}
 @Test void nonsensitiveProfileChangesPreserveSession(){var a=login();identity.updateProfile(a.accountId(),new IdentityService.ProfileUpdate("Updated","Name",null,"Address",null,null));assertThat(access(a.accessToken()).getStatusCode().value()).isEqualTo(200);}
 @Test void legacyJwtWithoutSessionIsRejected(){var a=login();String legacy=jwt.createAccessToken(a.accountId(),a.email(),Role.GANADERO);assertThat(access(legacy).getStatusCode().value()).isEqualTo(401);}
 @Test void sessionCannotBeSubstitutedAcrossUsers(){var a=login();var b=login();String forgedContext=jwt.createAccessToken(a.accountId(),a.email(),Role.GANADERO,sid(b.accessToken()),clock.instant(),clock.instant().plusSeconds(100));assertThat(access(forgedContext).getStatusCode().value()).isEqualTo(401);}
 @Test void recognizedSessionStillEnforcesRoles(){var vet=login(Role.VETERINARIO);var headers=new HttpHeaders();headers.setBearerAuth(vet.accessToken());var result=http.exchange("/api/v1/farms",HttpMethod.POST,new HttpEntity<>(Map.of("name","Farm"),headers),JsonNode.class);assertThat(result.getStatusCode().value()).isEqualTo(403);}
 @Test void missingSessionStorageFailsClosedAsServiceError(){var a=login();doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic outage")).when(repository).authorized(any(),any(),any(),any());try{var r=access(a.accessToken());assertThat(r.getStatusCode().value()).isEqualTo(503);assertThat(r.getBody().path("errorCode").asText()).isEqualTo("AUTHENTICATION_UNAVAILABLE");}finally{reset(repository);}}
 @Test void rotationFailureRollsBackConsumptionAndSuccessor(){var a=login();doThrow(new IllegalStateException("synthetic cipher failure")).when(crypto).encrypt(any(),anyString());try{assertThatThrownBy(()->identity.refresh(a.refreshToken(),UUID.randomUUID())).isInstanceOf(IllegalStateException.class);}finally{reset(crypto);}assertThat(repository.token(RefreshToken.digest(a.refreshToken())).orElseThrow().consumedAt()).isNull();assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_session_tokens WHERE session_id=?",Integer.class,sid(a.accessToken()))).isEqualTo(1);assertThat(refresh(a.refreshToken(),UUID.randomUUID()).getStatusCode().value()).isEqualTo(200);}
 @Test void storedRecoverableTokensAreEncrypted(){var a=login();UUID op=UUID.randomUUID();var b=identity.refresh(a.refreshToken(),op);byte[] encrypted=repository.token(RefreshToken.digest(a.refreshToken())).orElseThrow().recovery();String stored=new String(encrypted,java.nio.charset.StandardCharsets.ISO_8859_1);assertThat(stored.contains(b.accessToken())||stored.contains(b.refreshToken())).isFalse();assertThat(encrypted.length).isGreaterThan(28);}
 @Test void expiredRecoveryIsClearedWithoutDeletingReuseEvidence(){var a=login();identity.refresh(a.refreshToken(),UUID.randomUUID());clock.advance(60);sessions.cleanRecovery();var token=repository.token(RefreshToken.digest(a.refreshToken())).orElseThrow();assertThat(token.recovery()).isNull();assertThat(token.consumedAt()).isNotNull();assertThat(token.successorDigest()).isNotNull();}
 @Test void operationIdentifierAloneCannotRecover(){var a=login();UUID op=UUID.randomUUID();identity.refresh(a.refreshToken(),op);assertThat(refresh("not-the-original-token",op).getStatusCode().value()).isEqualTo(401);assertThat(access(a.accessToken()).getStatusCode().value()).isEqualTo(200);}
 @Test void refreshRequiresOperationIdentifier(){var a=login();assertThat(http.postForEntity("/api/v1/auth/refresh-token",Map.of("refreshToken",a.refreshToken()),JsonNode.class).getStatusCode().value()).isEqualTo(400);}
 @Test void revocationSurvivesServiceReconstruction(){var a=login();logout(a.refreshToken());var reconstructed=new SessionService(repository,accounts,jwt,crypto,clock,new org.springframework.jdbc.datasource.DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));assertThat(reconstructed.authorized(sid(a.accessToken()),a.accountId(),Role.GANADERO)).isFalse();}

 @Test void operationCannotBeReusedForAnotherRotation(){var a=login();UUID op=UUID.randomUUID();var b=identity.refresh(a.refreshToken(),op);invalid(()->identity.refresh(b.refreshToken(),op));assertThat(access(b.accessToken()).getStatusCode().value()).isEqualTo(401);}
 @Test void accountStatusConcurrentWithRefreshNeverResurrects() throws Exception {var a=login();concurrent(()->refresh(a.refreshToken(),UUID.randomUUID()),()->{jdbc.update("UPDATE identity_accounts SET status='SUSPENDIDA' WHERE id=?",a.accountId());return ResponseEntity.ok().build();});jdbc.update("UPDATE identity_accounts SET status='ACTIVA' WHERE id=?",a.accountId());assertThat(access(a.accessToken()).getStatusCode().value()).isEqualTo(401);}
 @Test void incompleteConfirmationFailsClosedWithoutAnotherChain(){var a=login();UUID op=UUID.randomUUID();doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic confirmation outage")).when(repository).confirmRecovery(anyString(),any());try{assertThatThrownBy(()->identity.refresh(a.refreshToken(),op)).isInstanceOf(org.springframework.dao.DataAccessException.class);}finally{reset(repository);}assertThat(repository.token(RefreshToken.digest(a.refreshToken())).orElseThrow().consumedAt()).isNotNull();assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_session_tokens WHERE session_id=?",Integer.class,sid(a.accessToken()))).isEqualTo(2);clock.advance(61);sessions.cleanRecovery();invalid(()->identity.refresh(a.refreshToken(),op));assertThat(access(a.accessToken()).getStatusCode().value()).isEqualTo(401);}
 @Test void sessionOperationsNeverLogRawCredentials(){var a=login();var root=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);var capture=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();capture.start();root.addAppender(capture);try{var b=identity.refresh(a.refreshToken(),UUID.randomUUID());logout(b.refreshToken());String messages=capture.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).collect(java.util.stream.Collectors.joining("\n"));assertThat(messages.contains(a.accessToken())||messages.contains(a.refreshToken())||messages.contains(b.accessToken())||messages.contains(b.refreshToken())).isFalse();}finally{root.detachAppender(capture);capture.stop();}}

 @Test void accountEmailChangeRevokesAllSessions(){var a=login();var b=identity.login(a.email(),"Test-Password1!");jdbc.update("UPDATE identity_accounts SET email=? WHERE id=?",UUID.randomUUID()+"@example.invalid",a.accountId());assertThat(access(a.accessToken()).getStatusCode().value()).isEqualTo(401);assertThat(access(b.accessToken()).getStatusCode().value()).isEqualTo(401);}
 @Test void roleRoundTripCannotReviveOldSession(){var a=login();jdbc.update("UPDATE identity_accounts SET role='VETERINARIO' WHERE id=?",a.accountId());jdbc.update("UPDATE identity_accounts SET role='GANADERO' WHERE id=?",a.accountId());assertThat(access(a.accessToken()).getStatusCode().value()).isEqualTo(401);}
 @Test void lossAndRestorationOfVerificationCannotReviveSession(){var a=login();jdbc.update("UPDATE identity_accounts SET verified_at=NULL WHERE id=?",a.accountId());jdbc.update("UPDATE identity_accounts SET verified_at=now() WHERE id=?",a.accountId());assertThat(access(a.accessToken()).getStatusCode().value()).isEqualTo(401);}
 @Test void failureAfterConsumptionRollsBackWholeRotation(){var a=login();doAnswer(call->{call.callRealMethod();throw new IllegalStateException("synthetic post-consumption failure");}).when(repository).consume(anyString(),any(),anyString(),any(byte[].class),any());try{assertThatThrownBy(()->identity.refresh(a.refreshToken(),UUID.randomUUID())).isInstanceOf(org.springframework.dao.DataAccessException.class).hasRootCauseMessage("synthetic post-consumption failure");}finally{reset(repository);}assertThat(repository.token(RefreshToken.digest(a.refreshToken())).orElseThrow().consumedAt()).isNull();assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_session_tokens WHERE session_id=?",Integer.class,sid(a.accessToken()))).isEqualTo(1);}
 @Test void historyRetainedUntilAfterAbsoluteExpiry(){var a=login();UUID id=sid(a.accessToken());identity.refresh(a.refreshToken(),UUID.randomUUID());clock.advance(7*86400);sessions.cleanRecovery();assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_session_tokens WHERE session_id=?",Integer.class,id)).isEqualTo(2);clock.advance(7*86400+1);sessions.cleanRecovery();assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_session_tokens WHERE session_id=?",Integer.class,id)).isZero();}
 @Test void databaseRejectsRefreshExpiryBeyondSession(){var a=login();assertThatThrownBy(()->jdbc.update("INSERT INTO identity_session_tokens(token_digest,session_id,created_at,expires_at) VALUES (?,?,?,?)","f".repeat(64),sid(a.accessToken()),java.sql.Timestamp.from(clock.instant()),java.sql.Timestamp.from(clock.instant().plusSeconds(8*86400)))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);}
 @Test void profileUpdateCannotOverwriteConcurrentSensitiveChange() throws Exception {var a=login();concurrent(()->{identity.updateProfile(a.accountId(),new IdentityService.ProfileUpdate("Updated","Name",null,null,null,null));return ResponseEntity.ok().build();},()->{jdbc.update("UPDATE identity_accounts SET password_hash=? WHERE id=?","new-synthetic-hash",a.accountId());return ResponseEntity.ok().build();});assertThat(jdbc.queryForObject("SELECT password_hash FROM identity_accounts WHERE id=?",String.class,a.accountId())).isEqualTo("new-synthetic-hash");assertThat(access(a.accessToken()).getStatusCode().value()).isEqualTo(401);}

 @Test void concurrentRemovalOfSessionMetadataIsInvalidNotServerError(){var a=login();UUID sessionId=sid(a.accessToken());String digest=RefreshToken.digest(a.refreshToken());doAnswer(call->{var result=call.callRealMethod();jdbc.update("DELETE FROM identity_sessions WHERE id=?",sessionId);return result;}).when(repository).accountForToken(digest);try{assertThat(refresh(a.refreshToken(),UUID.randomUUID()).getStatusCode().value()).isEqualTo(401);}finally{reset(repository);}}
 @Test void concurrentRemovalOfLogoutMetadataKeepsIdempotence(){var a=login();UUID sessionId=sid(a.accessToken());String digest=RefreshToken.digest(a.refreshToken());doAnswer(call->{var result=call.callRealMethod();jdbc.update("DELETE FROM identity_sessions WHERE id=?",sessionId);return result;}).when(repository).accountForToken(digest);try{assertThat(logout(a.refreshToken()).getStatusCode().value()).isEqualTo(200);}finally{reset(repository);}}
}
