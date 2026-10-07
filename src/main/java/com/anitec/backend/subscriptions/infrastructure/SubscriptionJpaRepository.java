package com.anitec.backend.subscriptions.infrastructure;

import com.anitec.backend.subscriptions.domain.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@code subscriptions_subscriptions}. */
public interface SubscriptionJpaRepository extends JpaRepository<SubscriptionJpaEntity, UUID> {

    Optional<SubscriptionJpaEntity> findByAccountId(UUID accountId);

    boolean existsByAccountId(UUID accountId);

    List<SubscriptionJpaEntity> findByEndsAtBeforeAndStatus(OffsetDateTime now, Subscription.Status status);

    List<SubscriptionJpaEntity> findByPlanId(UUID planId);
}
