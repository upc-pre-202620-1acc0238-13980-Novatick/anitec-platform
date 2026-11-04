package com.anitec.backend;

import com.anitec.backend.identity.domain.AccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"resend.api-key=", "payments.simulation-enabled=false"})
class IdentityVerificationTests {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired TestRestTemplate http;
    @Autowired AccountRepository accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.anitec.backend.identity.application.IdentityService identity;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    private final Map<String, String> passwords = new ConcurrentHashMap<>();

    private String register() {
        String email = "verification-" + UUID.randomUUID() + "@example.invalid";
        String password = "Test-" + UUID.randomUUID() + "aA1!";
        passwords.put(email, password);
        var response = http.postForEntity("/api/v1/auth/register", Map.of("name", "Test", "lastName", "Identity",
                "email", email, "password", password, "role", "GANADERO"), JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().path("data").has("verificationCode")).isFalse();
        return email;
    }

    private String code(String email) { return accounts.findByEmail(email).orElseThrow().getVerificationCode(); }
    private String wrong(String email) { return "000000".equals(code(email)) ? "000001" : "000000"; }
    private int attempts(String email) { return accounts.findByEmail(email).orElseThrow().getVerificationAttempts(); }
    private ResponseEntity<JsonNode> verify(String email, String code) {
        return http.postForEntity("/api/v1/auth/verify", Map.of("email", email, "code", code, "password", passwords.get(email)), JsonNode.class);
    }
    private ResponseEntity<JsonNode> resend(String email) {
        return http.postForEntity("/api/v1/auth/resend-code", Map.of("email", email, "password", passwords.get(email)), JsonNode.class);
    }

    private <T> List<T> concurrent(int count, IntFunction<T> call) throws Exception {
        try (var executor = Executors.newFixedThreadPool(count)) {
            var ready = new CountDownLatch(count);
            var start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timeout");
                    return call.apply(index);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            return results;
        }
    }

    @Test
    void failedAttemptIsCommittedDespiteHttpError() {
        String email = register();
        assertThat(verify(email, wrong(email)).getStatusCode().value()).isEqualTo(422);
        assertThat(attempts(email)).isEqualTo(1);
    }

    @Test
    void fifthFailureInvalidatesCodeAndCorrectCodeCannotBypassLimit() {
        String email = register(), correct = code(email), incorrect = wrong(email);
        for (int i = 1; i <= 5; i++) {
            var result = verify(email, incorrect);
            assertThat(result.getStatusCode().value()).isEqualTo(422);
            assertThat(attempts(email)).isEqualTo(i);
        }
        assertThat(code(email) == null).isTrue();
        assertThat(verify(email, correct).getStatusCode().value()).isEqualTo(422);
        assertThat(accounts.findByEmail(email).orElseThrow().isVerified()).isFalse();
    }

    @Test
    void exhaustedCodeCannotBypassResendCooldownOrWindowLimit() {
        String email = register();
        jdbc.update("UPDATE identity_accounts SET verification_attempts=5,verification_code=NULL,verification_resends=5 WHERE email=?", email);
        assertThat(resend(email).getStatusCode().value()).isEqualTo(429);
        jdbc.update("UPDATE identity_accounts SET verification_sent_at=now()-interval '2 minutes' WHERE email=?", email);
        assertThat(resend(email).getStatusCode().value()).isEqualTo(429);
        assertThat(accounts.findByEmail(email).orElseThrow().getVerificationResends()).isEqualTo(5);
    }

    @Test
    void malformedSixCharacterCodeIsRejectedBeforeBusinessAttempts() {
        String email = register();
        assertThat(verify(email, "abcdef").getStatusCode().value()).isEqualTo(400);
        assertThat(attempts(email)).isZero();
    }

    @Test
    void concurrentFailuresPersistExactlyFiveAttempts() throws Exception {
        String email = register(), incorrect = wrong(email);
        var responses = concurrent(12, i -> verify(email, incorrect));
        assertThat(responses).allSatisfy(response -> assertThat(response.getStatusCode().value()).isEqualTo(422));
        assertThat(attempts(email)).isEqualTo(5);
        assertThat(code(email) == null).isTrue();
        assertThat(responses.stream().filter(r -> r.getBody().path("errorCode").asText().equals("INVALID_CODE")).count()).isEqualTo(4);
    }

    @Test
    void expiredCodeIsRejectedAndResendRestoresVerification() {
        String email = register(), previous = code(email);
        jdbc.update("UPDATE identity_accounts SET verification_expires_at=now()-interval '1 second', verification_sent_at=now()-interval '11 minutes' WHERE email=?", email);
        var expired = verify(email, previous);
        assertThat(expired.getStatusCode().value()).isEqualTo(422);
        assertThat(expired.getBody().path("errorCode").asText()).isEqualTo("CODE_EXPIRED");
        assertThat(attempts(email)).isZero();
        assertThat(resend(email).getStatusCode().value()).isEqualTo(202);
        var pending = accounts.findByEmail(email).orElseThrow();
        assertThat(pending.getVerificationResends()).isEqualTo(1);
        assertThat(pending.getVerificationExpiresAt().isAfter(java.time.Instant.now())).isTrue();
        assertThat(verify(email, code(email)).getStatusCode().value()).isEqualTo(200);
        assertThat(accounts.findByEmail(email).orElseThrow().canSignIn()).isTrue();
    }

    @Test
    void onlyFiveResendsAreAllowedAndFailuresResetOnlyAfterValidResend() {
        String email = register(), incorrect = wrong(email);
        verify(email, incorrect);
        assertThat(resend(email).getStatusCode().value()).isEqualTo(429);
        for (int i = 1; i <= 5; i++) {
            jdbc.update("UPDATE identity_accounts SET verification_sent_at=now()-interval '2 minutes' WHERE email=?", email);
            assertThat(resend(email).getStatusCode().value()).isEqualTo(202);
            assertThat(accounts.findByEmail(email).orElseThrow().getVerificationResends()).isEqualTo(i);
            assertThat(attempts(email)).isZero();
        }
        jdbc.update("UPDATE identity_accounts SET verification_sent_at=now()-interval '2 minutes' WHERE email=?", email);
        assertThat(resend(email).getStatusCode().value()).isEqualTo(429);
    }

    @Test
    void concurrentResendsConsumeOneSlotAndKeepCooldown() throws Exception {
        String email = register();
        jdbc.update("UPDATE identity_accounts SET verification_sent_at=now()-interval '2 minutes' WHERE email=?", email);
        var responses = concurrent(8, i -> resend(email));
        assertThat(responses.stream().filter(r -> r.getStatusCode().value() == 202).count()).isEqualTo(1);
        assertThat(responses.stream().filter(r -> r.getStatusCode().value() == 429).count()).isEqualTo(7);
        assertThat(accounts.findByEmail(email).orElseThrow().getVerificationResends()).isEqualTo(1);
    }

    @Test
    void concurrentSuccessfulVerificationsActivateOnce() throws Exception {
        String email = register(), correct = code(email);
        var responses = concurrent(6, i -> verify(email, correct));
        assertThat(responses.stream().filter(r -> r.getStatusCode().value() == 200).count()).isEqualTo(1);
        assertThat(responses.stream().filter(r -> r.getStatusCode().value() == 422).count()).isEqualTo(5);
        assertThat(accounts.findByEmail(email).orElseThrow().isVerified()).isTrue();
    }

    @Test
    void legitimateResendAfterExhaustionResetsAttemptsButConsumesResendBudget() {
        String email = register(), incorrect = wrong(email);
        for (int i = 0; i < 5; i++) assertThat(verify(email, incorrect).getStatusCode().value()).isEqualTo(422);
        assertThat(resend(email).getStatusCode().value()).isEqualTo(429);
        assertThat(attempts(email)).isEqualTo(5);
        jdbc.update("UPDATE identity_accounts SET verification_sent_at=now()-interval '2 minutes' WHERE email=?", email);
        assertThat(resend(email).getStatusCode().value()).isEqualTo(202);
        assertThat(attempts(email)).isZero();
        assertThat(accounts.findByEmail(email).orElseThrow().getVerificationResends()).isEqualTo(1);
        assertThat(verify(email, code(email)).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void verificationAndResendShareTheSameSerializationBoundary() throws Exception {
        String email = register(), original = code(email);
        jdbc.update("UPDATE identity_accounts SET verification_sent_at=now()-interval '2 minutes' WHERE email=?", email);
        var responses = concurrent(2, i -> i == 0 ? verify(email, original) : resend(email));
        assertThat(responses.get(1).getStatusCode().value()).isIn(202, 409);
        var stored = accounts.findByEmail(email).orElseThrow();
        if (responses.getFirst().getStatusCode().value() == 200) {
            assertThat(responses.get(1).getStatusCode().value()).isEqualTo(409);
            assertThat(stored.isVerified()).isTrue();
            assertThat(stored.getVerificationResends()).isZero();
            assertThat(stored.getVerificationCode() == null).isTrue();
        } else {
            assertThat(responses.getFirst().getStatusCode().value()).isEqualTo(422);
            assertThat(stored.isVerified()).isFalse();
            assertThat(stored.getVerificationResends()).isEqualTo(1);
            assertThat(stored.getVerificationAttempts()).isEqualTo(1);
            assertThat(verify(email, code(email)).getStatusCode().value()).isEqualTo(200);
        }
    }

    @Test
    void verificationAndResendCannotReactivateSuspendedAccount() {
        String email = register();
        jdbc.update("UPDATE identity_accounts SET status='SUSPENDIDA',verification_sent_at=now()-interval '2 minutes' WHERE email=?", email);
        assertThat(resend(email).getStatusCode().value()).isEqualTo(409);
        assertThat(verify(email, code(email)).getStatusCode().value()).isEqualTo(422);
        assertThat(accounts.findByEmail(email).orElseThrow().canSignIn()).isFalse();
    }

    @Test
    void characterizeLogoutRevokesRefreshButDoesNotImmediatelyInvalidateAccess() throws Exception {
        // Characterizes current behavior for a product decision; does not prescribe future logout policy.
        String email = register();
        assertThat(verify(email, code(email)).getStatusCode().value()).isEqualTo(200);
        var login = http.postForEntity("/api/v1/auth/login", Map.of("email", email, "password", passwords.get(email)), JsonNode.class);
        assertThat(login.getStatusCode().value()).isEqualTo(200);
        String refresh = login.getBody().path("data").path("refreshToken").asText();
        String access = login.getBody().path("data").path("accessToken").asText();
        assertThat(http.postForEntity("/api/v1/auth/logout", Map.of("refreshToken", refresh), JsonNode.class)
                .getStatusCode().value()).isEqualTo(200);
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(http.getRootUri() + "/api/v1/auth/refresh-token"))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                        new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("refreshToken", refresh))))
                .build();
        var after = java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.discarding());
        assertThat(after.statusCode()).isEqualTo(401);
        var headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(access);
        var profile = http.exchange("/api/v1/account/me", org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(headers), JsonNode.class);
        assertThat(profile.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void publicResendNeverEnumeratesOrConsumesAccountBudget() {
        String pending = register(), active = register(), suspended = register();
        jdbc.update("UPDATE identity_accounts SET verification_resends=5 WHERE email=?", pending);
        jdbc.update("UPDATE identity_accounts SET status='ACTIVA',verified_at=now() WHERE email=?", active);
        jdbc.update("UPDATE identity_accounts SET status='SUSPENDIDA' WHERE email=?", suspended);
        String previous = code(pending);
        for (String email : List.of(pending, active, suspended, "missing@example.invalid")) {
            var response = http.postForEntity("/api/v1/auth/resend-code", Map.of("email",email),JsonNode.class);
            assertThat(response.getStatusCode().value()).isEqualTo(202);
            assertThat(response.getBody().path("message").asText()).isEqualTo("Solicitud recibida. Utilice el flujo de verificación autorizado.");
            assertThat(response.getBody().path("data").isMissingNode() || response.getBody().path("data").isNull()).isTrue();
            assertThat(response.getHeaders().containsKey("Retry-After")).isFalse();
        }
        var wrongPassword = http.postForEntity("/api/v1/auth/resend-code",Map.of("email",pending,"password","incorrect-password"),JsonNode.class);
        assertThat(wrongPassword.getStatusCode().value()).isEqualTo(202);
        assertThat(code(pending)).isEqualTo(previous);
        assertThat(accounts.findByEmail(pending).orElseThrow().getVerificationResends()).isEqualTo(5);
        // A normal application JWT does not authorize a pending account's verification context.
        var login=http.postForEntity("/api/v1/auth/login",Map.of("email",active,"password",passwords.get(active)),JsonNode.class);
        assertThat(login.getStatusCode().value()).isEqualTo(200);
        var headers=new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(login.getBody().path("data").path("accessToken").asText());
        var jwtOnly=http.exchange("/api/v1/auth/resend-code",org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(Map.of("email",pending),headers),JsonNode.class);
        assertThat(jwtOnly.getStatusCode().value()).isEqualTo(202);
        assertThat(jwtOnly.getBody().path("data").isMissingNode() || jwtOnly.getBody().path("data").isNull()).isTrue();
        assertThat(accounts.findByEmail(pending).orElseThrow().getVerificationResends()).isEqualTo(5);
    }

    @Test
    void publicVerificationFailuresDoNotRevealExpiredSuspendedOrMissingAccounts() {
        String expired=register(), suspended=register();
        jdbc.update("UPDATE identity_accounts SET verification_expires_at=now()-interval '1 minute' WHERE email=?",expired);
        jdbc.update("UPDATE identity_accounts SET status='SUSPENDIDA' WHERE email=?",suspended);
        for(String email:List.of(expired,suspended,"not-present@example.invalid")) {
            var response=http.postForEntity("/api/v1/auth/verify",Map.of("email",email,"code","000000"),JsonNode.class);
            assertThat(response.getStatusCode().value()).isEqualTo(422);
            assertThat(response.getBody().path("errorCode").asText()).isEqualTo("INVALID_CODE");
            assertThat(response.getBody().path("message").asText()).isEqualTo("No se pudo verificar el código");
        }
    }

    @Test
    void expiredWindowRecoversAndNewWindowConsumesFirstSlot() {
        String email=register();
        jdbc.update("UPDATE identity_accounts SET verification_window_started_at=now()-interval '25 hours', verification_sent_at=now()-interval '2 minutes', verification_resends=5, verification_attempts=5,verification_code=NULL WHERE email=?",email);
        var response=resend(email);
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody().path("data").path("remainingResends").asInt()).isEqualTo(4);
        assertThat(response.getBody().path("data").path("emailDelivery").asText()).isEqualTo("UNCONFIRMED");
        assertThat(response.getBody().path("data").has("verificationCode")).isFalse();
        var stored=accounts.findByEmail(email).orElseThrow();
        assertThat(stored.getVerificationResends()).isEqualTo(1);assertThat(stored.getVerificationAttempts()).isZero();
        assertThat(stored.getVerificationWindowStartedAt()).isEqualTo(stored.getVerificationSentAt());
        assertThat(resend(email).getStatusCode().value()).isEqualTo(429);
    }

    @Test
    void resendThrottleIncludesServerRetryAndDoesNotMoveWindowOrLastEmission() {
        String email=register();var before=accounts.findByEmail(email).orElseThrow();
        var cooldown=resend(email);
        assertThat(cooldown.getStatusCode().value()).isEqualTo(429);
        assertThat(Long.parseLong(cooldown.getHeaders().getFirst("Retry-After"))).isBetween(1L,60L);
        jdbc.update("UPDATE identity_accounts SET verification_resends=5 WHERE email=?",email);
        var exhausted=resend(email);
        assertThat(exhausted.getBody().path("errorCode").asText()).isEqualTo("VERIFICATION_RESEND_LIMIT");
        assertThat(Long.parseLong(exhausted.getHeaders().getFirst("Retry-After"))).isGreaterThan(80000);
        var after=accounts.findByEmail(email).orElseThrow();
        assertThat(after.getVerificationWindowStartedAt()).isEqualTo(before.getVerificationWindowStartedAt());
        assertThat(after.getVerificationSentAt()).isEqualTo(before.getVerificationSentAt());
        assertThat(after.getVerificationResends()).isEqualTo(5);
    }

    @Test
    void legacyWithoutRecoverableWindowTransitionsOnceAndKeepsKnownCooldown() {
        String email=register();
        jdbc.update("UPDATE identity_accounts SET verification_window_started_at=NULL, verification_resends=5,verification_attempts=3 WHERE email=?",email);
        assertThat(resend(email).getStatusCode().value()).isEqualTo(429);
        assertThat(attempts(email)).isEqualTo(3);
        var transition=accounts.findByEmail(email).orElseThrow().getVerificationPolicyMigratedAt();
        jdbc.update("UPDATE identity_accounts SET verification_sent_at=now()-interval '2 minutes' WHERE email=?",email);
        assertThat(resend(email).getStatusCode().value()).isEqualTo(202);
        var started=accounts.findByEmail(email).orElseThrow().getVerificationWindowStartedAt();
        jdbc.update("UPDATE identity_accounts SET verification_sent_at=now()-interval '2 minutes' WHERE email=?",email);
        assertThat(resend(email).getStatusCode().value()).isEqualTo(202);
        var stored=accounts.findByEmail(email).orElseThrow();
        assertThat(stored.getVerificationResends()).isEqualTo(2);
        assertThat(stored.getVerificationWindowStartedAt()).isEqualTo(started);
        assertThat(stored.getVerificationPolicyMigratedAt()).isEqualTo(transition);
    }

    @Test
    void concurrentLastResendOpportunityIsConsumedOnceAndItsCodeRemainsUsable() throws Exception {
        String email=register();
        jdbc.update("UPDATE identity_accounts SET verification_resends=4, verification_sent_at=now()-interval '2 minutes' WHERE email=?",email);
        var responses=concurrent(6,i->resend(email));
        assertThat(responses.stream().filter(r->r.getStatusCode().value()==202).count()).isEqualTo(1);
        assertThat(responses.stream().filter(r->r.getStatusCode().value()==429).count()).isEqualTo(5);
        assertThat(accounts.findByEmail(email).orElseThrow().getVerificationResends()).isEqualTo(5);
        assertThat(verify(email,code(email)).getStatusCode().value()).isEqualTo(200);
        assertThat(resend(email).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void transactionalFailureRollsBackWindowCounterAndCode() {
        String email=register();
        jdbc.update("UPDATE identity_accounts SET verification_window_started_at=now()-interval '25 hours',verification_resends=5,verification_attempts=2,verification_sent_at=now()-interval '2 minutes' WHERE email=?",email);
        var before=accounts.findByEmail(email).orElseThrow();
        org.assertj.core.api.Assertions.assertThatThrownBy(()->new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status->{
            identity.resendVerificationCode(email);throw new IllegalStateException("Intentional rollback");
        })).isInstanceOf(IllegalStateException.class);
        var after=accounts.findByEmail(email).orElseThrow();
        assertThat(after.getVerificationCode().equals(before.getVerificationCode())).isTrue();
        assertThat(after.getVerificationAttempts()).isEqualTo(2);assertThat(after.getVerificationResends()).isEqualTo(5);
        assertThat(after.getVerificationWindowStartedAt()).isEqualTo(before.getVerificationWindowStartedAt());
        assertThat(after.getVerificationSentAt()).isEqualTo(before.getVerificationSentAt());
    }

    @Test
    void nonPendingUnverifiedAccountCannotUseRecoveryToActivateItself() {
        String email=register();jdbc.update("UPDATE identity_accounts SET status='ACTIVA',verified_at=NULL WHERE email=?",email);
        assertThat(resend(email).getStatusCode().value()).isEqualTo(409);
        assertThat(verify(email,code(email)).getStatusCode().value()).isEqualTo(422);
        assertThat(accounts.findByEmail(email).orElseThrow().canSignIn()).isFalse();
    }
}
