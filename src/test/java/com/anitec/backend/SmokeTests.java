package com.anitec.backend;

import com.anitec.backend.identity.domain.AccountRepository;
import com.anitec.backend.subscriptions.application.SubscriptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests (spec section 14): auth, livestock, linking, care and
 * subscriptions happy paths + their core business rules, running against a
 * real PostgreSQL provided by Testcontainers (Docker required).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SmokeTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String PASSWORD = "Test1234!";

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    SubscriptionService subscriptionService;

    private final HttpClient http = HttpClient.newHttpClient();

    // ------------------------------------------------------------- helpers

    record Res(int status, JsonNode body) {
        JsonNode data() {
            return body.path("data");
        }

        String errorCode() {
            return body.path("errorCode").asText();
        }
    }

    record Seeded(String email, String token) {
    }

    Res call(String method, String path, Object body, String token) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + path))
                    .header("Content-Type", "application/json");
            if (token != null) {
                builder.header("Authorization", "Bearer " + token);
            }
            String json = body == null ? null : mapper.writeValueAsString(body);
            builder.method(method, json == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json));
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode node = response.body() == null || response.body().isBlank()
                    ? mapper.createObjectNode()
                    : mapper.readTree(response.body());
            return new Res(response.statusCode(), node);
        } catch (Exception ex) {
            throw new IllegalStateException("HTTP " + method + " " + path + " failed", ex);
        }
    }

    Res get(String path, String token) {
        return call("GET", path, null, token);
    }

    Res post(String path, Object body, String token) {
        return call("POST", path, body, token);
    }

    Res put(String path, Object body, String token) {
        return call("PUT", path, body, token);
    }

    Res patch(String path, Object body, String token) {
        return call("PATCH", path, body, token);
    }

    Res delete(String path, String token) {
        return call("DELETE", path, null, token);
    }

    String uniqueEmail(String tag) {
        return tag + "-" + UUID.randomUUID() + "@test.anitec.pe";
    }

    Res register(String email, String role) {
        return post("/api/v1/auth/register", Map.of(
                "name", "Test", "lastName", "User", "email", email,
                "password", PASSWORD, "role", role), null);
    }

    /** Registers, verifies and logs in; returns e-mail + access token. */
    Seeded seed(String role, String tag) {
        String email = uniqueEmail(tag);
        Res created = register(email, role);
        assertThat(created.status()).isEqualTo(201);
        String code = accountRepository.findByEmail(email).orElseThrow().getVerificationCode();
        Res verified = post("/api/v1/auth/verify", Map.of("email", email, "code", code), null);
        assertThat(verified.status()).isEqualTo(200);
        Res login = post("/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), null);
        assertThat(login.status()).isEqualTo(200);
        return new Seeded(email, login.data().path("accessToken").asText());
    }

    String loginToken(String email) {
        Res login = post("/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), null);
        assertThat(login.status()).isEqualTo(200);
        return login.data().path("accessToken").asText();
    }

    String firstSpeciesId(String token) {
        Res species = get("/api/v1/species", token);
        assertThat(species.status()).isEqualTo(200);
        return species.data().get(0).path("id").asText();
    }

    String createFarm(String token, String name) {
        Res farm = post("/api/v1/farms", Map.of("name", name, "location", "Test City"), token);
        assertThat(farm.status()).isEqualTo(201);
        return farm.data().path("id").asText();
    }

    Res createAnimal(String token, String farmId, String speciesId, String code) {
        return post("/api/v1/animals", Map.of(
                "farmId", farmId, "code", code, "speciesId", speciesId,
                "sex", "HEMBRA", "name", "Animal " + code,
                "birthDate", "2021-03-12"), token);
    }

    String linkPair(String farmerEmail, String farmerToken, String vetEmail, String vetToken) {
        Res invitation = post("/api/v1/invitations", Map.of("vetEmail", vetEmail), farmerToken);
        assertThat(invitation.status()).isEqualTo(201);
        assertThat(invitation.data().path("emailDelivery").asText()).isEqualTo("SENT");
        String invitationId = invitation.data().path("invitationId").asText();

        Res pending = get("/api/v1/invitations/pending", vetToken);
        assertThat(pending.status()).isEqualTo(200);
        assertThat(pending.data()).isNotEmpty();

        Res respond = put("/api/v1/invitations/" + invitationId + "/respond",
                Map.of("action", "ACCEPT"), vetToken);
        assertThat(respond.status()).isEqualTo(200);

        Res links = get("/api/v1/vet-links", farmerToken);
        assertThat(links.status()).isEqualTo(200);
        return links.data().path("links").get(0).path("linkId").asText();
    }

    // ------------------------------------------------------------ test 1

    @Test
    void authFlowRegisterVerifyLoginRefreshLogout() {
        String email = uniqueEmail("auth");
        Res created = register(email, "GANADERO");
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.data().path("emailDelivery").asText()).isEqualTo("SENT");
        String verificationCode = codeOf(email);

        // weak password rejected with the standard envelope (400)
        Res weak = post("/api/v1/auth/register", Map.of(
                "name", "A", "lastName", "B", "email", uniqueEmail("weak"),
                "password", "weak", "role", "GANADERO"), null);
        assertThat(weak.status()).isEqualTo(400);
        assertThat(weak.body().path("success").asBoolean()).isFalse();
        assertThat(weak.errorCode()).isEqualTo("VALIDATION_ERROR");

        // ADMIN cannot self-register
        Res admin = register(uniqueEmail("admin-role"), "ADMIN");
        assertThat(admin.status()).isEqualTo(400);

        // login before verification is rejected
        Res early = post("/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), null);
        assertThat(early.status()).isEqualTo(401);
        assertThat(early.errorCode()).isEqualTo("EMAIL_NOT_VERIFIED");

        // duplicate e-mail -> 409
        Res duplicate = register(email, "GANADERO");
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.errorCode()).isEqualTo("EMAIL_ALREADY_REGISTERED");

        // verify + login
        Res verified = post("/api/v1/auth/verify", Map.of("email", email, "code", verificationCode), null);
        assertThat(verified.status()).isEqualTo(200);
        Res wrongPassword = post("/api/v1/auth/login",
                Map.of("email", email, "password", "Another123!"), null);
        assertThat(wrongPassword.status()).isEqualTo(401);
        assertThat(wrongPassword.errorCode()).isEqualTo("INVALID_CREDENTIALS");

        Res login = post("/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), null);
        assertThat(login.status()).isEqualTo(200);
        String access1 = login.data().path("accessToken").asText();
        String refresh1 = login.data().path("refreshToken").asText();
        assertThat(access1).isNotBlank();

        // profile
        Res me = get("/api/v1/account/me", access1);
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.data().path("role").asText()).isEqualTo("GANADERO");
        assertThat(me.data().path("badge").asText()).isEqualTo("Ganadero");
        assertThat(me.data().path("emailVerified").asBoolean()).isTrue();

        // refresh rotates the token; the old one stops working
        Res refreshed = post("/api/v1/auth/refresh-token", Map.of("refreshToken", refresh1), null);
        assertThat(refreshed.status()).isEqualTo(200);
        String refresh2 = refreshed.data().path("refreshToken").asText();
        Res replay = post("/api/v1/auth/refresh-token", Map.of("refreshToken", refresh1), null);
        assertThat(replay.status()).isEqualTo(401);

        // logout revokes server side
        Res logout = post("/api/v1/auth/logout", Map.of("refreshToken", refresh2), null);
        assertThat(logout.status()).isEqualTo(200);
        Res afterLogout = post("/api/v1/auth/refresh-token", Map.of("refreshToken", refresh2), null);
        assertThat(afterLogout.status()).isEqualTo(401);

        // unauthenticated request -> 401 envelope
        Res anonymous = get("/api/v1/account/me", null);
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(anonymous.body().path("success").asBoolean()).isFalse();
    }

    private String codeOf(String email) {
        return accountRepository.findByEmail(email).orElseThrow().getVerificationCode();
    }

    // ------------------------------------------------------------ test 2

    @Test
    void livestockInventoryCapacityAndIsolation() {
        Seeded farmer = seed("GANADERO", "farm");
        String other = seed("GANADERO", "other").token();
        String speciesId = firstSpeciesId(farmer.token());
        String farmId = createFarm(farmer.token(), "Fundo Pruebas");

        // register 15 animals (free plan limit)
        List<String> animalIds = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            Res created = createAnimal(farmer.token(), farmId, speciesId, String.format("BOV-%03d", i));
            assertThat(created.status()).as("animal %d", i).isEqualTo(201);
            animalIds.add(created.data().path("id").asText());
        }

        // duplicate code within the farm -> 409 (even for a new registration)
        Res duplicate = createAnimal(farmer.token(), farmId, speciesId, "BOV-001");
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.errorCode()).isEqualTo("DUPLICATE_ANIMAL_CODE");

        // 16th animal blocked by the plan limit (decision #15: free = 15)
        Res overLimit = createAnimal(farmer.token(), farmId, speciesId, "BOV-999");
        assertThat(overLimit.status()).isEqualTo(422);
        assertThat(overLimit.errorCode()).isEqualTo("INVENTORY_LIMIT_REACHED");

        // inventory view
        Res inventory = get("/api/v1/animals", farmer.token());
        assertThat(inventory.status()).isEqualTo(200);
        assertThat(inventory.data().path("totalActive").asLong()).isEqualTo(15);
        assertThat(inventory.data().path("content")).hasSize(15);
        Res searched = get("/api/v1/animals?search=BOV-001", farmer.token());
        assertThat(searched.data().path("content")).hasSize(1);

        Res capacity = get("/api/v1/animals/capacity", farmer.token());
        assertThat(capacity.data().path("allowedAnimals").asInt()).isEqualTo(15);
        assertThat(capacity.data().path("activeAnimals").asInt()).isEqualTo(15);

        // animal detail with farm name
        String animalId = animalIds.get(0);
        Res detail = get("/api/v1/animals/" + animalId, farmer.token());
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.data().path("farmName").asText()).isEqualTo("Fundo Pruebas");
        assertThat(detail.data().path("observationsCount").asInt()).isEqualTo(0);

        // observations (farmer authorship)
        Res observation = post("/api/v1/animals/" + animalId + "/observations",
                Map.of("text", "Se adapta bien al lote."), farmer.token());
        assertThat(observation.status()).isEqualTo(201);
        assertThat(observation.data().path("authorName").asText()).isEqualTo("Test User");
        Res observations = get("/api/v1/animals/" + animalId + "/observations", farmer.token());
        assertThat(observations.data()).hasSize(1);

        // deactivation frees exactly one slot and is idempotent
        Res deactivate = delete("/api/v1/animals/" + animalId, farmer.token());
        assertThat(deactivate.status()).isEqualTo(200);
        assertThat(deactivate.data().path("status").asText()).isEqualTo("INACTIVO");
        Res deactivateAgain = delete("/api/v1/animals/" + animalId, farmer.token());
        assertThat(deactivateAgain.status()).isEqualTo(200);
        capacity = get("/api/v1/animals/capacity", farmer.token());
        assertThat(capacity.data().path("activeAnimals").asInt()).isEqualTo(14);

        Res newAnimal = createAnimal(farmer.token(), farmId, speciesId, "BOV-016");
        assertThat(newAnimal.status()).isEqualTo(201);

        // isolation: another farmer cannot read or modify
        assertThat(get("/api/v1/animals/" + animalId, other).status()).isEqualTo(403);
        assertThat(put("/api/v1/animals/" + animalId, Map.of(
                "code", "BOV-001", "speciesId", speciesId, "sex", "MACHO"), other).status()).isEqualTo(403);
        assertThat(delete("/api/v1/animals/" + animalId, other).status()).isEqualTo(403);
        assertThat(post("/api/v1/animals/" + animalId + "/observations",
                Map.of("text", "intruso"), other).status()).isEqualTo(403);
        assertThat(get("/api/v1/farms/" + farmId + "/animals", other).status()).isEqualTo(403);

        // future birth date rejected
        Res future = post("/api/v1/animals", Map.of(
                "farmId", farmId, "code", "BOV-777", "speciesId", speciesId,
                "sex", "MACHO", "birthDate", "2099-01-01"), farmer.token());
        assertThat(future.status()).isEqualTo(400);
    }

    // ------------------------------------------------------------ test 3

    @Test
    void linkingInvitationAcceptReadAndRevoke() {
        Seeded farmer = seed("GANADERO", "linkf");
        Seeded vet = seed("VETERINARIO", "linkv");
        String speciesId = firstSpeciesId(farmer.token());
        String farmId = createFarm(farmer.token(), "Fundo Vinculado");
        String animalId = createAnimal(farmer.token(), farmId, speciesId, "BOV-501")
                .data().path("id").asText();

        // unknown recipient rejected (US14 scenario 2)
        Res unknown = post("/api/v1/invitations",
                Map.of("vetEmail", "nobody-" + UUID.randomUUID() + "@test.anitec.pe"), farmer.token());
        assertThat(unknown.status()).isEqualTo(422);
        assertThat(unknown.errorCode()).isEqualTo("RECIPIENT_NOT_REGISTERED");

        // invite + duplicate protection
        Res invitation = post("/api/v1/invitations", Map.of("vetEmail", vet.email()), farmer.token());
        assertThat(invitation.status()).isEqualTo(201);
        assertThat(invitation.data().path("emailDelivery").asText()).isEqualTo("SENT");
        String invitationId = invitation.data().path("invitationId").asText();
        Res duplicated = post("/api/v1/invitations", Map.of("vetEmail", vet.email()), farmer.token());
        assertThat(duplicated.status()).isEqualTo(409);
        assertThat(duplicated.errorCode()).isEqualTo("INVITATION_ALREADY_EXISTS");

        // only the veterinarian profile sees pending invitations
        assertThat(get("/api/v1/invitations/pending", farmer.token()).status()).isEqualTo(403);
        Res pending = get("/api/v1/invitations/pending", vet.token());
        assertThat(pending.status()).isEqualTo(200);
        assertThat(pending.data()).hasSize(1);
        assertThat(pending.data().get(0).path("farmerName").asText()).isEqualTo("Test User");

        // accept consumes a capacity slot (US15)
        Res accept = put("/api/v1/invitations/" + invitationId + "/respond",
                Map.of("action", "ACCEPT"), vet.token());
        assertThat(accept.status()).isEqualTo(200);
        Res again = put("/api/v1/invitations/" + invitationId + "/respond",
                Map.of("action", "ACCEPT"), vet.token());
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.errorCode()).isEqualTo("INVALID_STATE_TRANSITION");

        Res linkCapacity = get("/api/v1/vet-links/capacity", vet.token());
        assertThat(linkCapacity.data().path("allowedRanchers").asInt()).isEqualTo(3);
        assertThat(linkCapacity.data().path("activeLinks").asInt()).isEqualTo(1);

        // my-farmers composition (name + farm + animal counts)
        Res myFarmers = get("/api/v1/vet-links/my-farmers", vet.token());
        assertThat(myFarmers.status()).isEqualTo(200);
        assertThat(myFarmers.data().path("activeLinksCount").asLong()).isEqualTo(1);
        JsonNode firstFarmer = myFarmers.data().path("farmers").get(0);
        assertThat(firstFarmer.path("farmerName").asText()).isEqualTo("Test User");
        assertThat(firstFarmer.path("farms").get(0).path("animalsCount").asLong()).isEqualTo(1);

        // the linked vet can read the animal; the inventory is scoped
        assertThat(get("/api/v1/animals/" + animalId, vet.token()).status()).isEqualTo(200);
        Res vetInventory = get("/api/v1/animals", vet.token());
        assertThat(vetInventory.data().path("content")).hasSize(1);

        // farmer revokes (US18): idempotent, frees one slot, access is cut
        Res links = get("/api/v1/vet-links", farmer.token());
        String linkId = links.data().path("links").get(0).path("linkId").asText();
        Res revoke = delete("/api/v1/vet-links/" + linkId, farmer.token());
        assertThat(revoke.status()).isEqualTo(200);
        Res revokeAgain = delete("/api/v1/vet-links/" + linkId, farmer.token());
        assertThat(revokeAgain.status()).isEqualTo(200);
        linkCapacity = get("/api/v1/vet-links/capacity", vet.token());
        assertThat(linkCapacity.data().path("activeLinks").asInt()).isEqualTo(0);

        assertThat(get("/api/v1/animals/" + animalId, vet.token()).status()).isEqualTo(403);
        Res emptyInventory = get("/api/v1/animals", vet.token());
        assertThat(emptyInventory.data().path("content")).isEmpty();
    }

    // ------------------------------------------------------------ test 4

    @Test
    void careVisitsAttentionsInstructionsAndNotifications() {
        Seeded farmer = seed("GANADERO", "caref");
        Seeded vet = seed("VETERINARIO", "carev");
        Seeded strangerVet = seed("VETERINARIO", "stranger");
        String speciesId = firstSpeciesId(farmer.token());
        String farmId = createFarm(farmer.token(), "Fundo Care");
        String animalId = createAnimal(farmer.token(), farmId, speciesId, "BOV-601")
                .data().path("id").asText();
        linkPair(farmer.email(), farmer.token(), vet.email(), vet.token());

        // scheduling requires an active link (US07)
        Res noLink = post("/api/v1/visits/schedule", Map.of(
                "animalId", animalId,
                "scheduledAt", OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).toString(),
                "reason", "Control"), strangerVet.token());
        assertThat(noLink.status()).isEqualTo(403);

        Res scheduled = post("/api/v1/visits/schedule", Map.of(
                "animalId", animalId,
                "scheduledAt", OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).toString(),
                "reason", "Control general"), vet.token());
        assertThat(scheduled.status()).isEqualTo(201);
        String visitId = scheduled.data().path("id").asText();
        assertThat(scheduled.data().path("type").asText()).isEqualTo("VISITA");

        // past visit date rejected
        Res pastVisit = post("/api/v1/visits/schedule", Map.of(
                "animalId", animalId,
                "scheduledAt", OffsetDateTime.now(ZoneOffset.UTC).minusDays(1).toString()), vet.token());
        assertThat(pastVisit.status()).isEqualTo(400);

        Res agenda = get("/api/v1/visits", vet.token());
        assertThat(agenda.status()).isEqualTo(200);
        assertThat(agenda.data()).isNotEmpty();
        assertThat(agenda.data().get(0).path("farmerName").asText()).isEqualTo("Test User");

        // only the vet profile may register attentions (route rule)
        assertThat(post("/api/v1/attentions", Map.of(
                "animalId", animalId, "title", "X"), farmer.token()).status()).isEqualTo(403);
        // ... and only linked vets (application rule, US09)
        Res strangerAttention = post("/api/v1/attentions", Map.of(
                "animalId", animalId, "title", "Intruso"), strangerVet.token());
        assertThat(strangerAttention.status()).isEqualTo(403);

        String yesterday = LocalDate.now().minusDays(1).toString();
        Res attention = post("/api/v1/attentions", Map.of(
                "animalId", animalId,
                "visitId", visitId,
                "title", "Control general",
                "description", "Revisión preventiva completa y control de peso.",
                "attentionDate", yesterday,
                "treatments", List.of(Map.of("description", "Vitamina B12 inyectable 5ml", "appliedDate", yesterday)),
                "vaccinations", List.of(Map.of("name", "Aftosa", "appliedDate", yesterday)),
                "instructions", "Mantener hidratación constante."), vet.token());
        assertThat(attention.status()).isEqualTo(201);
        String attentionId = attention.data().path("id").asText();
        assertThat(attention.data().path("instructions").path("content").asText())
                .isEqualTo("Mantener hidratación constante.");

        // registering the attention completed the scheduled visit (US09)
        Res visitAfter = get("/api/v1/visits", vet.token());
        boolean anyCompleted = false;
        for (JsonNode node : visitAfter.data()) {
            if ("COMPLETADA".equals(node.path("status").asText())) {
                anyCompleted = true;
            }
        }
        assertThat(anyCompleted).isTrue();

        // treatments/vaccinations add-ons (US10/US11)
        Res treatment = post("/api/v1/attentions/" + attentionId + "/treatments",
                Map.of("description", "Rehidratación oral", "appliedDate", yesterday), vet.token());
        assertThat(treatment.status()).isEqualTo(200);
        Res vaccination = post("/api/v1/attentions/" + attentionId + "/vaccinations",
                Map.of("name", "Brucelosis", "appliedDate", yesterday), vet.token());
        assertThat(vaccination.status()).isEqualTo(200);

        // instructions update creates a new version (US20)
        Res updated = put("/api/v1/attentions/" + attentionId + "/instructions",
                Map.of("content", "Aislamiento 72 horas y control de peso diario."), vet.token());
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.data().path("content").asText())
                .isEqualTo("Aislamiento 72 horas y control de peso diario.");

        // farmer reads instructions + history (US13/US21)
        Res instructions = get("/api/v1/attentions/" + attentionId + "/instructions", farmer.token());
        assertThat(instructions.status()).isEqualTo(200);
        assertThat(instructions.data().path("content").asText())
                .isEqualTo("Aislamiento 72 horas y control de peso diario.");
        Res history = get("/api/v1/animals/" + animalId + "/medical-history", farmer.token());
        assertThat(history.status()).isEqualTo(200);
        assertThat(history.data()).hasSize(1);
        assertThat(history.data().get(0).path("treatments")).hasSize(2);
        assertThat(history.data().get(0).path("vaccinations")).hasSize(2);

        // follow-up control (US12) and visit status transitions
        Res followUp = post("/api/v1/visits/follow-up", Map.of(
                "attentionId", attentionId,
                "scheduledAt", OffsetDateTime.now(ZoneOffset.UTC).plusDays(5).toString()), vet.token());
        assertThat(followUp.status()).isEqualTo(201);
        assertThat(followUp.data().path("type").asText()).isEqualTo("CONTROL");

        Res cancelled = patch("/api/v1/visits/" + visitId + "/status",
                Map.of("status", "CANCELADA"), vet.token());
        assertThat(cancelled.status()).isEqualTo(409); // already COMPLETADA

        // in-app notifications for the farmer (visit + instructions)
        Res notifications = get("/api/v1/notifications", farmer.token());
        assertThat(notifications.status()).isEqualTo(200);
        List<String> types = new ArrayList<>();
        notifications.data().path("content").forEach(node -> types.add(node.path("type").asText()));
        assertThat(types).contains("VISIT_SCHEDULED", "CARE_INSTRUCTIONS_REGISTERED",
                "CARE_INSTRUCTIONS_UPDATED");
        String firstNotification = notifications.data().path("content").get(0).path("id").asText();
        Res read = patch("/api/v1/notifications/" + firstNotification + "/read", null, farmer.token());
        assertThat(read.status()).isEqualTo(200);
    }

    // ------------------------------------------------------------ test 5

    @Test
    void subscriptionsCheckoutLimitsAndExpiry() {
        Seeded farmer = seed("GANADERO", "subf");
        Seeded vet = seed("VETERINARIO", "subv");

        // plans per profile (decision #15)
        Res farmerPlans = get("/api/v1/subscription/plans", farmer.token());
        assertThat(farmerPlans.status()).isEqualTo(200);
        String farmerPremium = null;
        String farmerFree = null;
        for (JsonNode plan : farmerPlans.data()) {
            if ("PREMIUM".equals(plan.path("type").asText())) {
                farmerPremium = plan.path("id").asText();
                assertThat(plan.path("capacityLimit").asInt()).isEqualTo(150);
                assertThat(plan.path("price").decimalValue()).isEqualByComparingTo("19.90");
            } else {
                farmerFree = plan.path("id").asText();
                assertThat(plan.path("capacityLimit").asInt()).isEqualTo(15);
            }
        }
        assertThat(farmerPremium).isNotNull();

        Res vetPlans = get("/api/v1/subscription/plans", vet.token());
        String vetPremium = null;
        for (JsonNode plan : vetPlans.data()) {
            if ("PREMIUM".equals(plan.path("type").asText())) {
                vetPremium = plan.path("id").asText();
                assertThat(plan.path("capacityLimit").asInt()).isEqualTo(25);
            }
        }
        assertThat(vetPremium).isNotNull();

        // plan/profile mismatch rejected BEFORE any payment (US23 scenario 3)
        Res mismatch = post("/api/v1/subscription/premium", Map.of("planId", vetPremium), farmer.token());
        assertThat(mismatch.status()).isEqualTo(422);
        assertThat(mismatch.errorCode()).isEqualTo("PLAN_PROFILE_MISMATCH");

        // the free plan cannot be purchased
        Res notPremium = post("/api/v1/subscription/premium", Map.of("planId", farmerFree), farmer.token());
        assertThat(notPremium.status()).isEqualTo(400);

        // checkout -> declined: state unchanged (US23 scenario 2)
        Res checkout = post("/api/v1/subscription/premium", Map.of("planId", farmerPremium), farmer.token());
        assertThat(checkout.status()).isEqualTo(200);
        String checkoutId = checkout.data().path("checkoutId").asText();
        assertThat(checkout.data().path("status").asText()).isEqualTo("PENDING");
        Res declined = post("/api/v1/subscription/premium/confirm",
                Map.of("checkoutId", checkoutId, "outcome", "DECLINED"), farmer.token());
        assertThat(declined.status()).isEqualTo(200);
        assertThat(declined.data().path("paymentStatus").asText()).isEqualTo("DECLINED");
        JsonNode subscription = get("/api/v1/subscription/my-subscription", farmer.token()).data();
        assertThat(subscription.path("planType").asText()).isEqualTo("GRATUITO");
        assertThat(subscription.path("allowedCapacity").asInt()).isEqualTo(15);
        assertThat(subscription.path("revision").asLong()).isEqualTo(1);

        // approved checkout: premium + revision bump + capacity in livestock (decision #12)
        Res checkout2 = post("/api/v1/subscription/premium", Map.of("planId", farmerPremium), farmer.token());
        String checkoutId2 = checkout2.data().path("checkoutId").asText();
        Res approved = post("/api/v1/subscription/premium/confirm",
                Map.of("checkoutId", checkoutId2, "outcome", "APPROVED"), farmer.token());
        assertThat(approved.status()).isEqualTo(200);
        assertThat(approved.data().path("paymentStatus").asText()).isEqualTo("APPROVED");
        subscription = get("/api/v1/subscription/my-subscription", farmer.token()).data();
        assertThat(subscription.path("planType").asText()).isEqualTo("PREMIUM");
        assertThat(subscription.path("allowedCapacity").asInt()).isEqualTo(150);
        assertThat(subscription.path("revision").asLong()).isEqualTo(2);
        assertThat(subscription.path("endsAt").isMissingNode()).isFalse();

        // idempotent replay (TS02): no duplicate effects
        Res replay = post("/api/v1/subscription/premium/confirm",
                Map.of("checkoutId", checkoutId2, "outcome", "APPROVED"), farmer.token());
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.data().path("paymentStatus").asText()).isEqualTo("APPROVED");
        subscription = get("/api/v1/subscription/my-subscription", farmer.token()).data();
        assertThat(subscription.path("revision").asLong()).isEqualTo(2);

        // livestock capacity follows the plan limit
        Res capacity = get("/api/v1/animals/capacity", farmer.token());
        assertThat(capacity.data().path("allowedAnimals").asInt()).isEqualTo(150);
        assertThat(capacity.data().path("lastPlanRevision").asLong()).isEqualTo(2);

        // purchasing the same premium plan again -> 409
        Res repurchase = post("/api/v1/subscription/premium", Map.of("planId", farmerPremium), farmer.token());
        assertThat(repurchase.status()).isEqualTo(409);

        // cancel renewal is idempotent and keeps premium (US24)
        Res cancel = post("/api/v1/subscription/cancel-renewal", null, farmer.token());
        assertThat(cancel.status()).isEqualTo(200);
        assertThat(cancel.data().path("renewalEnabled").asBoolean()).isFalse();
        assertThat(cancel.data().path("planType").asText()).isEqualTo("PREMIUM");
        Res cancelAgain = post("/api/v1/subscription/cancel-renewal", null, farmer.token());
        assertThat(cancelAgain.status()).isEqualTo(200);
        assertThat(cancelAgain.data().path("renewalEnabled").asBoolean()).isFalse();

        // premium activation notification
        Res notifications = get("/api/v1/notifications", farmer.token());
        List<String> types = new ArrayList<>();
        notifications.data().path("content").forEach(node -> types.add(node.path("type").asText()));
        assertThat(types).contains("PREMIUM_ACTIVATED");

        // force expiry: paid period over without renewal -> free limit restored
        jdbcTemplate.update(
                "UPDATE subscriptions_subscriptions SET ends_at = now() - INTERVAL '1 day' "
                        + "WHERE account_id = (SELECT id FROM identity_accounts WHERE email = ?)",
                farmer.email());
        int expired = subscriptionService.expireDueSubscriptions();
        assertThat(expired).isGreaterThanOrEqualTo(1);

        subscription = get("/api/v1/subscription/my-subscription", farmer.token()).data();
        assertThat(subscription.path("planType").asText()).isEqualTo("GRATUITO");
        assertThat(subscription.path("status").asText()).isEqualTo("VENCIDA");
        assertThat(subscription.path("allowedCapacity").asInt()).isEqualTo(15);
        assertThat(subscription.path("revision").asLong()).isEqualTo(3);

        // ... and the livestock capacity was pushed back to the free limit
        capacity = get("/api/v1/animals/capacity", farmer.token());
        assertThat(capacity.data().path("allowedAnimals").asInt()).isEqualTo(15);
        assertThat(capacity.data().path("lastPlanRevision").asLong()).isEqualTo(3);
    }

    // ------------------------------------------------------------ test 6

    @Test
    void expiredInvitationNoLongerBlocksThePair() {
        Seeded farmer = seed("GANADERO", "expf");
        Seeded vet = seed("VETERINARIO", "expv");

        Res invitation = post("/api/v1/invitations", Map.of("vetEmail", vet.email()), farmer.token());
        assertThat(invitation.status()).isEqualTo(201);
        String invitationId = invitation.data().path("invitationId").asText();

        // a live duplicate is still rejected
        Res duplicated = post("/api/v1/invitations", Map.of("vetEmail", vet.email()), farmer.token());
        assertThat(duplicated.status()).isEqualTo(409);
        assertThat(duplicated.errorCode()).isEqualTo("INVITATION_ALREADY_EXISTS");

        // force the 7 day validity to lapse
        jdbcTemplate.update(
                "UPDATE linking_invitations SET expires_at = now() - INTERVAL '1 day' WHERE id = ?::uuid",
                invitationId);

        // the veterinarian can no longer answer it...
        Res respond = put("/api/v1/invitations/" + invitationId + "/respond",
                Map.of("action", "ACCEPT"), vet.token());
        assertThat(respond.status()).isEqualTo(422);
        assertThat(respond.errorCode()).isEqualTo("INVITATION_EXPIRED");
        // ... and it no longer shows as pending
        Res pending = get("/api/v1/invitations/pending", vet.token());
        assertThat(pending.status()).isEqualTo(200);
        assertThat(pending.data()).isEmpty();

        // the farmer can invite the same veterinarian again (was a permanent 409)
        Res resend = post("/api/v1/invitations", Map.of("vetEmail", vet.email()), farmer.token());
        assertThat(resend.status()).isEqualTo(201);
        String newInvitationId = resend.data().path("invitationId").asText();
        assertThat(newInvitationId).isNotEqualTo(invitationId);

        // ... and the replacement invitation works end to end
        Res accept = put("/api/v1/invitations/" + newInvitationId + "/respond",
                Map.of("action", "ACCEPT"), vet.token());
        assertThat(accept.status()).isEqualTo(200);
        Res links = get("/api/v1/vet-links", farmer.token());
        assertThat(links.data().path("links")).hasSize(1);
    }

    // ------------------------------------------------------------ test 7

    @Test
    void inputValidationRejectsOversizedAndIncompletePayloads() {
        Seeded farmer = seed("GANADERO", "validf");
        Seeded vet = seed("VETERINARIO", "validv");
        String speciesId = firstSpeciesId(farmer.token());
        String farmId = createFarm(farmer.token(), "Fundo Validación");

        // name longer than livestock_farms.name VARCHAR(150) -> 400, not a DB error
        Res farm = post("/api/v1/farms", Map.of("name", "F".repeat(200)), farmer.token());
        assertThat(farm.status()).isEqualTo(400);
        assertThat(farm.errorCode()).isEqualTo("VALIDATION_ERROR");

        // breed longer than livestock_animals.breed VARCHAR(100) -> 400
        Res animal = post("/api/v1/animals", Map.of(
                "farmId", farmId, "code", "BOV-801", "speciesId", speciesId,
                "sex", "HEMBRA", "breed", "B".repeat(150)), farmer.token());
        assertThat(animal.status()).isEqualTo(400);
        assertThat(animal.errorCode()).isEqualTo("VALIDATION_ERROR");

        // a nested treatment without appliedDate used to reach the DB (NOT NULL) as a 500
        linkPair(farmer.email(), farmer.token(), vet.email(), vet.token());
        String animalId = createAnimal(farmer.token(), farmId, speciesId, "BOV-802")
                .data().path("id").asText();
        Res attention = post("/api/v1/attentions", Map.of(
                "animalId", animalId,
                "title", "Control",
                "treatments", List.of(Map.of("description", "Vitamina B12"))), vet.token());
        assertThat(attention.status()).isEqualTo(400);
        assertThat(attention.errorCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(attention.body().path("details")).isNotEmpty();
    }
}
