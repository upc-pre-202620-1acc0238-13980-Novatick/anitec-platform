package com.anitec.backend;

import com.anitec.backend.identity.domain.AccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "email.delivery.mode=disabled", "email.delivery.worker-enabled=false",
        "identity.password-recovery.enabled=false", "payments.simulation-enabled=false",
        "spring.flyway.placeholders.demo_seed=false", "logging.level.root=ERROR"})
class FarmerUpcomingVisitsTests {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired AccountRepository accounts;
    @Autowired JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newHttpClient();
    private static final String UPCOMING = "/visits/upcoming";

    // Sessions and verification codes remain in memory and are never assertion messages.
    record User(UUID id, String email, String token) {}
    record Response(int status, JsonNode body) {
        JsonNode data() { return body.path("data"); }
    }
    record Scenario(User farmer, User vet, String farm, String animal) {}

    private Response call(String method, String path, Object payload, User user) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (user != null) request.header("Authorization", "Bearer " + user.token());
        request.method(method, payload == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)));
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), mapper.readTree(response.body()));
    }

    private String created(Response response) {
        assertThat(response.status()).isEqualTo(201);
        return response.data().path("id").asText();
    }

    private User user(String role) throws Exception {
        String email = UUID.randomUUID() + "@example.invalid";
        String password = "Test-" + UUID.randomUUID() + "aA1!";
        String name = role.equals("GANADERO") ? "Ganadero" : "Veterinario";
        assertThat(call("POST", "/auth/register", Map.of("name", name, "lastName", "Prueba",
                "email", email, "password", password, "role", role), null).status()).isEqualTo(201);
        var account = accounts.findByEmail(email).orElseThrow();
        assertThat(call("POST", "/auth/verify", Map.of("email", email,
                "code", TestVerificationCodes.code(account)), null).status()).isEqualTo(200);
        var login = call("POST", "/auth/login", Map.of("email", email, "password", password), null);
        assertThat(login.status()).isEqualTo(200);
        return new User(account.getId(), email, login.data().path("accessToken").asText());
    }

    private String farm(User farmer) throws Exception {
        return created(call("POST", "/farms", Map.of("name", "Finca QA"), farmer));
    }

    private String animal(User farmer, String farm) throws Exception {
        String species = call("GET", "/species", null, farmer).data().get(0).path("id").asText();
        return created(call("POST", "/animals", Map.of("farmId", farm, "speciesId", species,
                "sex", "HEMBRA", "name", "Nina"), farmer));
    }

    private Scenario scenario() throws Exception {
        var farmer = user("GANADERO");
        var vet = user("VETERINARIO");
        var invitation = call("POST", "/invitations", Map.of("vetEmail", vet.email()), farmer);
        assertThat(invitation.status()).isEqualTo(201);
        String id = invitation.data().path("invitationId").asText();
        assertThat(call("PUT", "/invitations/" + id + "/respond", Map.of("action", "ACCEPT"), vet)
                .status()).isEqualTo(200);
        String farm = farm(farmer);
        return new Scenario(farmer, vet, farm, animal(farmer, farm));
    }

    private String visit(Scenario scenario, String when) throws Exception {
        return created(call("POST", "/visits/schedule", Map.of("animalId", scenario.animal(),
                "scheduledAt", when, "reason", "Evaluación"), scenario.vet()));
    }

    private List<String> ids(JsonNode data) {
        var ids = new ArrayList<String>();
        data.forEach(item -> ids.add(item.path("id").asText()));
        return ids;
    }

    @Test void ownerReceivesRequiredFieldsWithoutUnnecessaryPersonalData() throws Exception {
        var s = scenario();
        String visit = visit(s, "2035-06-01T14:30:00Z");
        var response = call("GET", UPCOMING, null, s.farmer());
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().path("success").asBoolean()).isTrue();
        assertThat(ids(response.data())).containsExactly(visit);
        var item = response.data().get(0);
        var fields = new ArrayList<String>();
        item.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("id", "animalId", "animalName", "animalCode",
                "farmerName", "vetName", "type", "scheduledAt", "status", "reason");
        assertThat(item.path("animalId").asText()).isEqualTo(s.animal());
        assertThat(item.path("animalName").asText()).isEqualTo("Nina");
        assertThat(item.path("animalCode").asText()).isNotBlank();
        assertThat(item.path("farmerName").asText()).isEqualTo("Ganadero Prueba");
        assertThat(item.path("vetName").asText()).isEqualTo("Veterinario Prueba");
        assertThat(item.path("type").asText()).isEqualTo("VISITA");
        assertThat(item.path("status").asText()).isEqualTo("PROGRAMADA");
        assertThat(item.path("reason").asText()).isEqualTo("Evaluación");
    }

    @Test void farmerWithoutFarmsReceivesAnEmptyArray() throws Exception {
        var response = call("GET", UPCOMING, null, user("GANADERO"));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.data().isArray()).isTrue();
        assertThat(response.data()).isEmpty();
    }

    @Test void farmerWithAnimalsButNoVisitsReceivesAnEmptyArray() throws Exception {
        var s = scenario();
        assertThat(call("GET", UPCOMING, null, s.farmer()).data()).isEmpty();
    }

    @Test void otherFarmerCannotSelectAnotherOwnersVisitsUsingParameters() throws Exception {
        var s = scenario();
        String visit = visit(s, "2035-06-01T14:30:00Z");
        var other = user("GANADERO");
        var response = call("GET", UPCOMING + "?ownerId=" + s.farmer().id()
                + "&farmerId=" + s.farmer().id() + "&animalId=" + s.animal()
                + "&page=0&size=1000&status=PROGRAMADA", null, other);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.data()).isEmpty();
        assertThat(ids(call("GET", UPCOMING, null, s.farmer()).data())).containsExactly(visit);
    }

    @Test void rejectsAnonymousAndVeterinarianWithOfficialErrors() throws Exception {
        var anonymous = call("GET", UPCOMING, null, null);
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(anonymous.body().path("errorCode").asText()).isEqualTo("INVALID_CREDENTIALS");
        var vet = call("GET", UPCOMING, null, user("VETERINARIO"));
        assertThat(vet.status()).isEqualTo(403);
        assertThat(vet.body().path("errorCode").asText()).isEqualTo("FORBIDDEN");
    }

    @Test void returnsAllFutureVisitsAcrossFarmsInChronologicalOrderBeyondThirtyDays() throws Exception {
        var s = scenario();
        String last = visit(s, "2035-08-01T14:30:00Z");
        String first = visit(s, "2035-06-01T14:30:00Z");
        String secondFarm = farm(s.farmer());
        var otherAnimal = new Scenario(s.farmer(), s.vet(), secondFarm, animal(s.farmer(), secondFarm));
        String middle = visit(otherAnimal, "2035-07-01T14:30:00Z");
        assertThat(ids(call("GET", UPCOMING, null, s.farmer()).data())).containsExactly(first, middle, last);
    }

    @Test void excludesPastCancelledAndCompletedVisits() throws Exception {
        var s = scenario();
        // Let a valid appointment expire naturally; do not bypass PostgreSQL's future-date constraint.
        visit(s, Instant.now().plusSeconds(5).toString());
        Thread.sleep(6000);
        String cancelled = visit(s, "2035-07-01T14:30:00Z");
        assertThat(call("PATCH", "/visits/" + cancelled + "/status", Map.of("status", "CANCELADA"), s.vet())
                .status()).isEqualTo(200);
        String completed = visit(s, "2035-08-01T14:30:00Z");
        created(call("POST", "/attentions", Map.of("animalId", s.animal(), "visitId", completed,
                "title", "Consulta", "description", "Evaluación estable",
                "attentionDate", LocalDate.now(ZoneOffset.UTC).toString()), s.vet()));
        String future = visit(s, "2035-09-01T14:30:00Z");
        assertThat(ids(call("GET", UPCOMING + "?from=2000-01-01T00:00:00Z&status=CANCELADA", null, s.farmer())
                .data())).containsExactly(future);
    }

    @Test void serializesOffsetInputAsUtc() throws Exception {
        var s = scenario();
        visit(s, "2035-06-01T09:30:00-05:00");
        String date = call("GET", UPCOMING, null, s.farmer()).data().get(0).path("scheduledAt").asText();
        assertThat(date).isEqualTo("2035-06-01T14:30:00Z");
        assertThat(Instant.parse(date)).isEqualTo(Instant.parse("2035-06-01T14:30:00Z"));
    }

    @Test void excludesAnAnimalWhoseCurrentOwnerDoesNotMatchAppointmentOwner() throws Exception {
        var s = scenario();
        String visit = visit(s, "2035-06-01T14:30:00Z");
        var other = user("GANADERO");
        jdbc.update("UPDATE care_appointments SET owner_id=? WHERE id=?", other.id(), UUID.fromString(visit));
        farm(other);
        assertThat(call("GET", UPCOMING, null, other).data()).isEmpty();
        assertThat(call("GET", UPCOMING, null, s.farmer()).data()).isEmpty();
    }

    @Test void excludesAnAnimalWhoseFarmIsNotOwnedByTheCaller() throws Exception {
        var s = scenario();
        visit(s, "2035-06-01T14:30:00Z");
        var other = user("GANADERO");
        jdbc.update("UPDATE livestock_farms SET farmer_id=? WHERE id=?", other.id(), UUID.fromString(s.farm()));
        assertThat(call("GET", UPCOMING, null, s.farmer()).data()).isEmpty();
    }

    @Test void includesFollowUpsAndKeepsVeterinarianAgendaContractUnchanged() throws Exception {
        var s = scenario();
        String visit = visit(s, "2035-06-01T14:30:00Z");
        String attention = created(call("POST", "/attentions", Map.of("animalId", s.animal(),
                "title", "Consulta", "description", "Evaluación estable",
                "attentionDate", LocalDate.now(ZoneOffset.UTC).toString()), s.vet()));
        String followUp = created(call("POST", "/visits/follow-up", Map.of("attentionId", attention,
                "scheduledAt", "2035-07-01T14:30:00Z"), s.vet()));
        var farmer = call("GET", UPCOMING, null, s.farmer());
        assertThat(ids(farmer.data())).containsExactly(visit, followUp);
        assertThat(farmer.data().get(1).path("type").asText()).isEqualTo("CONTROL");
        var agenda = call("GET", "/visits?from=2035-01-01T00:00:00Z&to=2036-01-01T00:00:00Z", null, s.vet());
        assertThat(agenda.status()).isEqualTo(200);
        assertThat(ids(agenda.data())).containsExactly(visit, followUp);
        var fields = new ArrayList<String>();
        agenda.data().get(0).fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("id", "animalId", "animalName", "farmerId", "farmerName",
                "type", "scheduledAt", "status", "reason");
        assertThat(call("GET", "/visits", null, s.farmer()).status()).isEqualTo(403);
        assertThat(call("GET", "/visits", null, user("VETERINARIO")).data()).isEmpty();
    }
}
