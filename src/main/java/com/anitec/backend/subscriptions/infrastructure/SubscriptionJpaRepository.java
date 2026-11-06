package com.anitec.backend.subscriptions.infrastructure;

import com.anitec.backend.subscriptions.domain.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@code subscriptions_subscriptions}. */
public interface SubscriptionJpaRepository extends JpaRepository<SubscriptionJpaEntity, UUID> {

    Optional<SubscriptionJpaEntity> findByAccountId(UUID accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SubscriptionJpaEntity s where s.accountId = :accountId")
    Optional<SubscriptionJpaEntity> findByAccountIdForUpdate(@Param("accountId") UUID accountId);

    boolean existsByAccountId(UUID accountId);

    @Query("select s.accountId from SubscriptionJpaEntity s where s.endsAt <= :now and s.status = :status order by s.accountId")
    List<UUID> findDueAccountIds(@Param("now") OffsetDateTime now, @Param("status") Subscription.Status status);

}
