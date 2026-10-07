package com.anitec.backend.subscriptions.infrastructure;

import com.anitec.backend.subscriptions.domain.PaymentGateway;
import com.anitec.backend.subscriptions.domain.Plan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Fake payment adapter (decision #13): simulates a checkout reference and
 * provider-side operations. A real Stripe adapter (test mode + webhook,
 * ACL translating Stripe events into this model) plugs in behind the same
 * {@link PaymentGateway} port later.
 */
@Component
public class FakePaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(FakePaymentGateway.class);

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
