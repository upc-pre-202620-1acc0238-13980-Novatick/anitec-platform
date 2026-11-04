package com.anitec.backend;

import com.anitec.backend.shared.domain.DomainException;
import com.anitec.backend.subscriptions.application.SubscriptionService;
import com.anitec.backend.subscriptions.domain.*;
import com.anitec.backend.subscriptions.infrastructure.*;
import com.anitec.backend.shared.application.DomainEventDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentGatewayConfigurationTests {
    private PaymentGateway gateway(boolean enabled, String... profiles) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("payments.simulation-enabled", Boolean.toString(enabled));
        environment.setActiveProfiles(profiles);
        return new PaymentGatewayConfiguration().paymentGateway(environment);
    }

    @Test
    void productionNeverSelectsFakeEvenWithMixedProfilesAndExplicitFlag() {
        assertThat(gateway(true, "prod", "dev", "test")).isInstanceOf(UnavailablePaymentGateway.class);
        assertThat(gateway(false, "prod")).isInstanceOf(UnavailablePaymentGateway.class);
    }

    @Test
    void simulationRequiresBothExplicitOptInAndAnAuthorizedProfile() {
        assertThat(gateway(true)).isInstanceOf(UnavailablePaymentGateway.class);
        assertThat(gateway(true, "staging")).isInstanceOf(UnavailablePaymentGateway.class);
        assertThat(gateway(false, "dev")).isInstanceOf(UnavailablePaymentGateway.class);
        assertThat(gateway(false, "test")).isInstanceOf(UnavailablePaymentGateway.class);
        assertThat(gateway(true, "dev")).isInstanceOf(FakePaymentGateway.class);
        assertThat(gateway(true, "test")).isInstanceOf(FakePaymentGateway.class);
    }

    @Test
    void unavailableProviderReturnsControlled503ForAllOperations() {
        PaymentGateway gateway = gateway(false, "prod");
        assertUnavailable(() -> gateway.createCheckout(UUID.randomUUID(), null));
        assertUnavailable(() -> gateway.cancelRenewal(UUID.randomUUID()));
        assertUnavailable(() -> gateway.getCurrentState(UUID.randomUUID()));
        assertUnavailable(gateway::requireSimulatedConfirmation);
    }

    @Test
    void clientApprovalOrDeclineCannotReachPersistenceWithoutSimulator() {
        var plans = mock(PlanRepository.class);
        var subscriptions = mock(SubscriptionRepository.class);
        var payments = mock(PaymentRepository.class);
        var events = mock(DomainEventDispatcher.class);
        var service = new SubscriptionService(plans, subscriptions, payments, gateway(false, "prod"), events);
        for (var outcome : SubscriptionService.PaymentOutcome.values()) {
            assertUnavailable(() -> service.confirmPayment(UUID.randomUUID(), "existing-checkout", outcome));
        }
        verifyNoInteractions(plans, subscriptions, payments, events);
    }

    private void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(DomainException.class, ex -> {
            assertThat(ex.status().value()).isEqualTo(503);
            assertThat(ex.errorCode()).isEqualTo("PAYMENTS_UNAVAILABLE");
        });
    }
}
