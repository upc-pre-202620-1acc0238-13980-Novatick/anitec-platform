package com.anitec.backend.subscriptions.domain;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

/** Repository port for payments. */
public interface PaymentRepository {

    Payment save(Payment payment);

    Optional<Payment> findByGatewayRef(String gatewayRef);

    /** Called only while holding the owning subscription lock. */
    List<Payment> findPendingBySubscriptionId(UUID subscriptionId);
}
