package com.anitec.backend.subscriptions.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;
import com.anitec.backend.subscriptions.domain.Payment;
import java.util.UUID;

/** Spring Data repository for {@code subscriptions_payments}. */
public interface PaymentJpaRepository extends JpaRepository<PaymentJpaEntity, UUID> {

    Optional<PaymentJpaEntity> findByGatewayRef(String gatewayRef);

    List<PaymentJpaEntity> findBySubscriptionIdAndStatusOrderByCreatedAtAscIdAsc(UUID subscriptionId, Payment.Status status);
}
