package com.anitec.backend;

import com.anitec.backend.subscriptions.domain.PaymentGateway;
import com.anitec.backend.subscriptions.infrastructure.UnavailablePaymentGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"resend.api-key=", "payments.simulation-enabled=false"})
class DevelopmentSecurityIntegrationTests {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TestRestTemplate http;
    @Autowired
    PaymentGateway paymentGateway;

    @Test
    void developmentStartsWithHealthAndSwaggerAndPaymentsDisabledByDefault() {
        assertThat(paymentGateway).isInstanceOf(UnavailablePaymentGateway.class);
        assertThat(http.getForEntity("/actuator/health", String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(http.getForEntity("/swagger-ui/index.html", String.class).getStatusCode().value()).isEqualTo(200);
    }
}
