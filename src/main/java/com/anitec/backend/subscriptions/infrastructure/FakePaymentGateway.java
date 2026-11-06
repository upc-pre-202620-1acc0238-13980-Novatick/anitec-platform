package com.anitec.backend.subscriptions.infrastructure;

import com.anitec.backend.subscriptions.domain.PaymentGateway;
import com.anitec.backend.subscriptions.domain.Plan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Fake payment adapter (decision #13): simulates a checkout reference and
 * operations for the academic MVP. No funds move and no external provider is contacted.
 * A financial provider would require a separate, future integration behind the port.
 */
public class FakePaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(FakePaymentGateway.class);

    @Override
    public void requireSimulatedConfirmation() {
        // This adapter is wired only for explicitly opted-in dev/test environments.
    }

    @Override
    public String createCheckout(UUID accountId, Plan plan) {
        String reference = "fake_ck_" + UUID.randomUUID();
        log.info("[FAKE PAYMENT] checkout created account={} plan={} ref={}",
                accountId, plan.getId(), reference);
        return reference;
    }

    @Override
    public void cancelRenewal(UUID accountId) {
        log.info("[FAKE PAYMENT] renewal cancelled for account {}", accountId);
    }

    @Override
    public String getCurrentState(UUID accountId) {
        return "ACTIVE";
    }
}
