package com.anitec.backend.subscriptions.infrastructure;

import com.anitec.backend.shared.domain.DomainException;
import com.anitec.backend.subscriptions.domain.PaymentGateway;
import com.anitec.backend.subscriptions.domain.Plan;

import java.util.UUID;

/** Fail closed until a real provider is installed. */
public class UnavailablePaymentGateway implements PaymentGateway {
    @Override
    public String createCheckout(UUID accountId, Plan plan) {
        throw unavailable();
    }

    @Override
    public void cancelRenewal(UUID accountId) {
        throw unavailable();
    }

    @Override
    public String getCurrentState(UUID accountId) {
        throw unavailable();
    }

    private static DomainException unavailable() {
        return DomainException.unavailable("PAYMENTS_UNAVAILABLE", "Los pagos no están disponibles en este entorno");
    }
}
