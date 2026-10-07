package com.anitec.backend.subscriptions.infrastructure;

import com.anitec.backend.subscriptions.domain.Subscription;
import com.anitec.backend.subscriptions.domain.SubscriptionRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Infrastructure adapter for {@link SubscriptionRepository}. */
@Component
public class SubscriptionRepositoryImpl implements SubscriptionRepository {

    private final SubscriptionJpaRepository jpa;

    public SubscriptionRepositoryImpl(SubscriptionJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Subscription save(Subscription subscription) {
        return jpa.save(SubscriptionJpaEntity.fromDomain(subscription)).toDomain();
    }

    @Override
    public Optional<Subscription> findByAccountId(UUID accountId) {
        return jpa.findByAccountId(accountId).map(SubscriptionJpaEntity::toDomain);
    }

    @Override
    public boolean existsByAccountId(UUID accountId) {
        return jpa.existsByAccountId(accountId);
    }

    @Override
    public List<Subscription> findDue(Instant now) {
        return jpa.findByEndsAtBeforeAndStatus(SubscriptionJpaEntity.toJdbc(now), Subscription.Status.ACTIVA).stream()
                .map(SubscriptionJpaEntity::toDomain).toList();
    }

    @Override
    public List<Subscription> findByPlanId(UUID planId) {
        return jpa.findByPlanId(planId).stream().map(SubscriptionJpaEntity::toDomain).toList();
    }
}
