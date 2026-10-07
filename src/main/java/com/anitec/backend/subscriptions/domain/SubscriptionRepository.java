package com.anitec.backend.subscriptions.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Repository port for the Subscription aggregate. */
public interface SubscriptionRepository {

    Subscription save(Subscription subscription);

    Optional<Subscription> findByAccountId(UUID accountId);

    boolean existsByAccountId(UUID accountId);

    List<Subscription> findDue(Instant now);

    List<Subscription> findByPlanId(UUID planId);
}
