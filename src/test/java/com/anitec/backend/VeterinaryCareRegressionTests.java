package com.anitec.backend;

import com.anitec.backend.identity.domain.AccountRepository;
import com.anitec.backend.care.domain.CareRecord;
import com.anitec.backend.shared.domain.DomainException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Diagnostic regressions against US07-US09, US12-US13 and US18.
 * Preserves the diagnostic cases and verifies the authorized production corrections.
 * The multiple-attention test characterizes behavior, not an approved cardinality rule.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"resend.api-key=", "payments.simulation-enabled=false"})
class VeterinaryCareRegressionTests {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired AccountRepository accounts;
    @Autowired JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newHttpClient();

    record Response(int status, JsonNode body) {
        JsonNode data() { return body.path("data"); }
    }

    // Tokens remain only in memory; never include sessions in assertion diagnostics.
    private static class User {
        String email;
        String token;
    }

    private static class Scenario {
        User farmer;
        User vet;
        String linkId;
        String animalId;
    }

    private Response call(String method, String path, Object payload, User user) {
        try {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                    .header("Content-Type", "application/json");
            if (user != null) builder.header("Authorization", "Bearer " + user.token);
            builder.method(method, payload == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)));
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body().isBlank()
                    ? mapper.createObjectNode() : mapper.readTree(response.body()));
        } catch (Exception failure) {
            throw new IllegalStateException("HTTP " + method + " " + path + " failed", failure);
        }
    }

    private String createdId(Response response) {
        assertThat(response.status()).isEqualTo(201);
        return response.data().path("id").asText();
    }

    private User user(String role) {
        var user = new User();
        user.email = "care-" + UUID.randomUUID() + "@example.invalid";
        String password = "Test-" + UUID.randomUUID() + "aA1!";
        assertThat(call("POST", "/auth/register", Map.of("name", "Care", "lastName", "Test",
                "email", user.email, "password", password, "role", role), null).status()).isEqualTo(201);
        String code = accounts.findByEmail(user.email).orElseThrow().getVerificationCode();
        assertThat(call("POST", "/auth/verify", Map.of("email", user.email, "code", code), null)
                .status()).isEqualTo(200);
        var login = call("POST", "/auth/login", Map.of("email", user.email, "password", password), null);
        assertThat(login.status()).isEqualTo(200);
        user.token = login.data().path("accessToken").asText();
        return user;
    }

    private String link(User farmer, User vet) {
        var invitation = call("POST", "/invitations", Map.of("vetEmail", vet.email), farmer);
        assertThat(invitation.status()).isEqualTo(201);
        String invitationId = invitation.data().path("invitationId").asText();
        assertThat(call("PUT", "/invitations/" + invitationId + "/respond",
                Map.of("action", "ACCEPT"), vet).status()).isEqualTo(200);
        var links = call("GET", "/vet-links", null, vet);
        assertThat(links.status()).isEqualTo(200);
        return links.data().path("links").get(0).path("linkId").asText();
    }

    private Scenario scenario() {
        var scenario = new Scenario();
        scenario.farmer = user("GANADERO");
        scenario.vet = user("VETERINARIO");
        scenario.linkId = link(scenario.farmer, scenario.vet);
        String farm = createdId(call("POST", "/farms", Map.of("name", "Disposable test farm"), scenario.farmer));
        var species = call("GET", "/species", null, scenario.farmer);
        assertThat(species.status()).isEqualTo(200);
        scenario.animalId = createdId(call("POST", "/animals", Map.of("farmId", farm,
                "speciesId", species.data().get(0).path("id").asText(), "code", "CARE-1",
                "sex", "HEMBRA", "name", "Clinical test animal"), scenario.farmer));
        return scenario;
    }

    private String visit(Scenario scenario) {
        return createdId(call("POST", "/visits/schedule", Map.of("animalId", scenario.animalId,
                "scheduledAt", Instant.now().plusSeconds(86400).toString(), "reason", "Evaluation"), scenario.vet));
    }

    private Map<String, Object> attention(Scenario scenario, String visitId) {
        var payload = new HashMap<String, Object>();
        payload.put("animalId", scenario.animalId);
        payload.put("title", "Clinical evaluation");
        payload.put("description", "Evaluation performed; stable condition");
        payload.put("attentionDate", LocalDate.now(ZoneOffset.UTC).minusDays(1).toString());
        if (visitId != null) payload.put("visitId", visitId);
        return payload;
    }

    private String attentionId(Scenario scenario, String visitId) {
        return createdId(call("POST", "/attentions", attention(scenario, visitId), scenario.vet));
    }

    private String control(Scenario scenario, String attentionId) {
        return createdId(call("POST", "/visits/follow-up", Map.of("attentionId", attentionId,
                "scheduledAt", Instant.now().plusSeconds(172800).toString()), scenario.vet));
    }

    private void revoke(Scenario scenario) {
        assertThat(call("DELETE", "/vet-links/" + scenario.linkId, null, scenario.farmer)
                .status()).isEqualTo(200);
    }

    private long attentionCount(String visitId) {
        return jdbc.queryForObject("SELECT count(*) FROM care_records WHERE appointment_id=?",
                Long.class, UUID.fromString(visitId));
    }

    private long animalAttentionCount(Scenario scenario) {
        return jdbc.queryForObject("SELECT count(*) FROM care_records WHERE animal_id=?",
                Long.class, UUID.fromString(scenario.animalId));
    }

    private String visitStatus(String visitId) {
        return jdbc.queryForObject("SELECT status FROM care_appointments WHERE id=?",
                String.class, UUID.fromString(visitId));
    }

    private void assertCannotCompleteWithoutAttention(Scenario scenario, String visitId) {
        assertThat(attentionCount(visitId)).isZero();
        var response = call("PATCH", "/visits/" + visitId + "/status", Map.of("status", "COMPLETADA"), scenario.vet);
        SoftAssertions.assertSoftly(soft -> {
            soft.assertThat(response.status()).as("completion without clinical record must be rejected")
                    .isEqualTo(409);
            soft.assertThat(response.body().path("errorCode").asText()).isEqualTo("ATTENTION_REQUIRED");
            soft.assertThat(visitStatus(visitId)).as("visit state must remain unchanged").isEqualTo("PROGRAMADA");
            soft.assertThat(attentionCount(visitId)).isZero();
        });
    }

    @Test
    void visitCannotBeCompletedWithoutClinicalAttention_US09() {
        var scenario = scenario();
        assertCannotCompleteWithoutAttention(scenario, visit(scenario));
    }

    @Test
    void followUpCannotBeCompletedWithoutNewClinicalAttention_US09() {
        var scenario = scenario();
        String original = attentionId(scenario, null);
        assertCannotCompleteWithoutAttention(scenario, control(scenario, original));
    }

    @Test
    void revokedVeterinarianCannotReadVisitsOrControlsInAgenda_US18() {
        var scenario = scenario();
        String visit = visit(scenario);
        String control = control(scenario, attentionId(scenario, null));
        revoke(scenario);
        var response = call("GET", "/visits", null, scenario.vet);
        // A collection may legitimately return 200 with revoked resources filtered out.
        assertThat(response.status()).isEqualTo(200);
        var ids = StreamSupport.stream(response.data().spliterator(), false)
                .map(node -> node.path("id").asText()).toList();
        assertThat(ids).as("agenda must not disclose revoked owner's visits or controls")
                .doesNotContain(visit, control);
        assertThat(visitStatus(visit)).isEqualTo("PROGRAMADA");
        assertThat(visitStatus(control)).isEqualTo("PROGRAMADA");
    }

    @Test
    void revokedVeterinarianCannotCancelAssignedVisit_US18() {
        var scenario = scenario();
        String visit = visit(scenario);
        revoke(scenario);
        var response = call("PATCH", "/visits/" + visit + "/status", Map.of("status", "CANCELADA"), scenario.vet);
        SoftAssertions.assertSoftly(soft -> {
            soft.assertThat(response.status()).as("revoked modification").isEqualTo(403);
            soft.assertThat(visitStatus(visit)).isEqualTo("PROGRAMADA");
        });
    }

    @Test
    void revokedVeterinarianCannotCompleteAssignedControl_US18() {
        var scenario = scenario();
        String control = control(scenario, attentionId(scenario, null));
        revoke(scenario);
        var response = call("PATCH", "/visits/" + control + "/status", Map.of("status", "COMPLETADA"), scenario.vet);
        SoftAssertions.assertSoftly(soft -> {
            soft.assertThat(response.status()).as("revoked completion").isEqualTo(403);
            soft.assertThat(visitStatus(control)).isEqualTo("PROGRAMADA");
        });
    }

    private void rejectIncompleteAttention(String field, boolean blank) {
        var scenario = scenario();
        var payload = attention(scenario, null);
        if (blank) payload.put(field, "   "); else payload.remove(field);
        var response = call("POST", "/attentions", payload, scenario.vet);
        SoftAssertions.assertSoftly(soft -> {
            soft.assertThat(response.status()).as("incomplete clinical attention must be rejected").isEqualTo(400);
            soft.assertThat(response.body().path("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
            soft.assertThat(animalAttentionCount(scenario)).as("no incomplete clinical record persisted").isZero();
        });
        LocalDate date = payload.containsKey("attentionDate")
                ? LocalDate.parse((String) payload.get("attentionDate")) : null;
        assertThatThrownBy(() -> CareRecord.register(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, "Evaluation", (String) payload.get("description"), date, Instant.now()))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.errorCode()).isEqualTo("VALIDATION_ERROR"));
    }

    @Test
    void attentionRequiresExplicitDate_US09() { rejectIncompleteAttention("attentionDate", false); }

    @Test
    void attentionRequiresDescription_US09() { rejectIncompleteAttention("description", false); }

    @Test
    void attentionRejectsBlankDescription_US09() { rejectIncompleteAttention("description", true); }

    @Test
    void revokedLinkAlreadyBlocksNewWritesAndClinicalReadsButPreservesHistory_US07_US12_US13_US18() {
        var scenario = scenario();
        String attention = attentionId(scenario, null);
        revoke(scenario);
        assertThat(call("POST", "/visits/schedule", Map.of("animalId", scenario.animalId,
                "scheduledAt", Instant.now().plusSeconds(86400).toString()), scenario.vet).status()).isEqualTo(403);
        assertThat(call("POST", "/visits/follow-up", Map.of("attentionId", attention,
                "scheduledAt", Instant.now().plusSeconds(86400).toString()), scenario.vet).status()).isEqualTo(403);
        assertThat(call("POST", "/attentions", attention(scenario, null), scenario.vet).status()).isEqualTo(403);
        assertThat(call("GET", "/attentions/" + attention, null, scenario.vet).status()).isEqualTo(403);
        assertThat(call("GET", "/animals/" + scenario.animalId + "/medical-history", null, scenario.vet)
                .status()).isEqualTo(403);
        var history = call("GET", "/animals/" + scenario.animalId + "/medical-history", null, scenario.farmer);
        assertThat(history.status()).isEqualTo(200);
        assertThat(history.data()).hasSize(1);
        assertThat(history.data().get(0).path("id").asText()).isEqualTo(attention);
    }

    @Test
    void anotherLinkedVeterinarianCannotManageAssignedVisitOrSeeItInOwnAgenda_US08() {
        var scenario = scenario();
        String visit = visit(scenario);
        User other = user("VETERINARIO");
        link(scenario.farmer, other);
        assertThat(call("PATCH", "/visits/" + visit + "/status", Map.of("status", "CANCELADA"), other)
                .status()).isEqualTo(403);
        assertThat(call("POST", "/attentions", attention(scenario, visit), other).status()).isEqualTo(403);
        var agenda = call("GET", "/visits", null, other);
        assertThat(agenda.status()).isEqualTo(200);
        assertThat(agenda.data()).isEmpty();
        assertThat(visitStatus(visit)).isEqualTo("PROGRAMADA");
        assertThat(attentionCount(visit)).isZero();
    }

    @Test
    void agendaOrdersVisitAndControlAndControlAttentionPreservesOriginal_US08_US09_US12() {
        var scenario = scenario();
        String visit = visit(scenario);
        String original = attentionId(scenario, visit);
        String control = control(scenario, original);
        var agenda = call("GET", "/visits", null, scenario.vet);
        assertThat(agenda.status()).isEqualTo(200);
        assertThat(agenda.data()).hasSize(2);
        assertThat(agenda.data().get(0).path("id").asText()).isEqualTo(visit);
        assertThat(agenda.data().get(1).path("id").asText()).isEqualTo(control);
        assertThat(agenda.data().get(1).path("type").asText()).isEqualTo("CONTROL");
        String followUpAttention = attentionId(scenario, control);
        assertThat(followUpAttention).isNotEqualTo(original);
        assertThat(visitStatus(control)).isEqualTo("COMPLETADA");
        assertThat(jdbc.queryForObject("SELECT origin_attention_id FROM care_appointments WHERE id=?",
                UUID.class, UUID.fromString(control))).isEqualTo(UUID.fromString(original));
        assertThat(attentionCount(visit)).isEqualTo(1);
        assertThat(attentionCount(control)).isEqualTo(1);
        // Repeating a legitimate completion remains idempotent, but authorization is always rechecked.
        assertThat(call("PATCH", "/visits/" + control + "/status", Map.of("status", "COMPLETADA"), scenario.vet)
                .status()).isEqualTo(200);
        var history = call("GET", "/animals/" + scenario.animalId + "/medical-history", null, scenario.farmer);
        assertThat(history.status()).isEqualTo(200);
        assertThat(history.data()).hasSize(2);
        revoke(scenario);
        assertThat(call("PATCH", "/visits/" + control + "/status", Map.of("status", "COMPLETADA"), scenario.vet)
                .status()).isEqualTo(403);
        assertThat(attentionCount(control)).isEqualTo(1);
    }

    @Test
    void pastSchedulingAndFutureClinicalDatesAreRejected_US07_US09_US12() {
        var scenario = scenario();
        String attention = attentionId(scenario, null);
        assertThat(call("POST", "/visits/schedule", Map.of("animalId", scenario.animalId,
                "scheduledAt", Instant.now().minusSeconds(60).toString()), scenario.vet).status()).isEqualTo(400);
        assertThat(call("POST", "/visits/follow-up", Map.of("attentionId", attention,
                "scheduledAt", Instant.now().minusSeconds(60).toString()), scenario.vet).status()).isEqualTo(400);
        var future = attention(scenario, null);
        future.put("attentionDate", LocalDate.now(ZoneOffset.UTC).plusDays(2).toString());
        assertThat(call("POST", "/attentions", future, scenario.vet).status()).isEqualTo(400);
        assertThatThrownBy(() -> CareRecord.register(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, "Evaluation", "Clinical description", LocalDate.now(ZoneOffset.UTC).plusDays(2), Instant.now()))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.errorCode()).isEqualTo("INVALID_DATE"));
        assertThat(animalAttentionCount(scenario)).isEqualTo(1);
    }

    @Test
    void cancelledVisitRejectsAttentionAndRollsBackClinicalInsert() {
        var scenario = scenario();
        String visit = visit(scenario);
        assertThat(call("PATCH", "/visits/" + visit + "/status", Map.of("status", "CANCELADA"), scenario.vet)
                .status()).isEqualTo(200);
        assertThat(call("POST", "/attentions", attention(scenario, visit), scenario.vet).status()).isEqualTo(409);
        assertThat(attentionCount(visit)).isZero();
        assertThat(visitStatus(visit)).isEqualTo("CANCELADA");
    }

    @Test
    void characterizeMultipleAttentionsForSameVisitWithoutApprovingCardinality() {
        var scenario = scenario();
        String visit = visit(scenario);
        String first = attentionId(scenario, visit);
        String second = attentionId(scenario, visit);
        assertThat(second).isNotEqualTo(first);
        assertThat(attentionCount(visit)).isEqualTo(2);
        assertThat(visitStatus(visit)).isEqualTo("COMPLETADA");
    }
}
