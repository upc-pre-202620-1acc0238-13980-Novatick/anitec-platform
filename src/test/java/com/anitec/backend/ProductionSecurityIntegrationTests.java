package com.anitec.backend;

import com.anitec.backend.identity.application.IdentityService;
import com.anitec.backend.identity.domain.AccountRepository;
import com.anitec.backend.shared.domain.Role;
import com.anitec.backend.shared.infrastructure.security.JwtService;
import com.anitec.backend.subscriptions.application.SubscriptionService;
import com.anitec.backend.subscriptions.domain.Payment;
import com.anitec.backend.subscriptions.domain.PaymentRepository;
import com.anitec.backend.subscriptions.infrastructure.UnavailablePaymentGateway;
import com.anitec.backend.subscriptions.domain.PaymentGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ProductionSecurityIntegrationTests {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void productionMigratesWithoutPrivilegedAccountsAndRejectsCheckoutAndClientApproval() throws Exception {
        var application = new SpringApplication(AnitecApplication.class);
        application.setLogStartupInfo(false);
        try (var context = application.run(
                "--spring.profiles.active=prod", "--server.port=0", "--spring.main.banner-mode=off",
                "--logging.level.root=ERROR", "--logging.level.com.anitec.backend=ERROR",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--jwt.secret=" + UUID.randomUUID() + UUID.randomUUID(),
                "--spring.flyway.placeholders.demo_seed=false", "--payments.simulation-enabled=false",
                "--resend.api-key=")) {
            assertThat(context.getBean(PaymentGateway.class)).isInstanceOf(UnavailablePaymentGateway.class);
            var jdbc = context.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_accounts", Integer.class)).isEqualTo(0);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class))
                    .isEqualTo(11);

            var identity = context.getBean(IdentityService.class);
            String email = "production-probe@example.invalid";
            var created = identity.register(new IdentityService.RegisterCommand("Test", "Probe", email,
                    "Probe-" + UUID.randomUUID() + "aA1!", Role.GANADERO));
            assertThat(created.emailDelivery()).isEqualTo("UNCONFIRMED");
            var account = context.getBean(AccountRepository.class).findByEmail(email).orElseThrow();
            identity.verifyEmail(email, account.getVerificationCode());
            String token = context.getBean(JwtService.class).createAccessToken(account.getId(), email, Role.GANADERO);
            var subscriptions = context.getBean(SubscriptionService.class);
            var before = subscriptions.mySubscription(account.getId());
            UUID premiumId = subscriptions.plansFor(Role.GANADERO).stream()
                    .filter(plan -> plan.type().equals("PREMIUM")).findFirst().orElseThrow().id();
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            var mapper = context.getBean(ObjectMapper.class);
            var http = HttpClient.newHttpClient();
            for (var requestData : Map.of(
                    "/api/v1/subscription/premium", Map.of("planId", premiumId),
                    "/api/v1/subscription/premium/confirm", Map.of("checkoutId", "previous-pending", "outcome", "APPROVED")
            ).entrySet()) {
                if (requestData.getKey().endsWith("confirm")) {
                    context.getBean(PaymentRepository.class).save(Payment.pending(before.subscriptionId(), account.getId(),
                            premiumId, "previous-pending", new java.math.BigDecimal("19.90"), "PEN", java.time.Instant.now()));
                }
                var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + requestData.getKey()))
                        .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(requestData.getValue()))).build();
                var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(503);
                assertThat(mapper.readTree(response.body()).path("errorCode").asText()).isEqualTo("PAYMENTS_UNAVAILABLE");
            }
            var after = subscriptions.mySubscription(account.getId());
            assertThat(after.planType()).isEqualTo("GRATUITO");
            assertThat(after.revision()).isEqualTo(before.revision());
            assertThat(after.allowedCapacity()).isEqualTo(15);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM subscriptions_payments WHERE status='APPROVED'", Integer.class))
                    .isZero();
        }
    }
}
