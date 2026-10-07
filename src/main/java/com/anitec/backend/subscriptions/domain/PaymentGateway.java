package com.anitec.backend.subscriptions.domain;

import java.util.UUID;

/**
 * Outbound payment port (report contract). This build ships the
 * FakePaymentGateway adapter (decision #13); a real Stripe adapter (test
 * mode + webhook) can be plugged here later behind the same interface.
 */
public interface PaymentGateway {

    /** Creates a checkout reference for the given account/plan. */
    String createCheckout(UUID accountId, Plan plan);

    /** Requests renewal cancellation at the provider (idempotent). */
    void cancelRenewal(UUID accountId);

    /** Current provider state for reconciliation (TS02). */
    String getCurrentState(UUID accountId);
}
