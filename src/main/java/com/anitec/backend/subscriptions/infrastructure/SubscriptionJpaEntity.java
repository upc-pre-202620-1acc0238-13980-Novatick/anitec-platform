package com.anitec.backend.subscriptions.infrastructure;

import com.anitec.backend.shared.domain.Role;
import com.anitec.backend.subscriptions.domain.Plan;
import com.anitec.backend.subscriptions.domain.Subscription;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Persistence model for {@code subscriptions_subscriptions} (Flyway V5). */
@Entity
@Table(name = "subscriptions_subscriptions")
@Getter
@Setter
@NoArgsConstructor
public class SubscriptionJpaEntity {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false, unique = true)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role profile;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_type", nullable = false)
    private Plan.PlanType planType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Subscription.Status status;

    @Column(name = "renewal_enabled", nullable = false)
    private boolean renewalEnabled;

    @Column(name = "allowed_capacity", nullable = false)
    private int allowedCapacity;

    @Column(nullable = false)
    private long revision;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "ends_at")
    private OffsetDateTime endsAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public static SubscriptionJpaEntity fromDomain(Subscription subscription) {
        SubscriptionJpaEntity entity = new SubscriptionJpaEntity();
        entity.setId(subscription.getId());
        entity.setAccountId(subscription.getAccountId());
        entity.setProfile(subscription.getProfile());
        entity.setPlanId(subscription.getPlanId());
        entity.setPlanType(subscription.getPlanType());
        entity.setStatus(subscription.getStatus());
        entity.setRenewalEnabled(subscription.isRenewalEnabled());
        entity.setAllowedCapacity(subscription.getAllowedCapacity());
        entity.setRevision(subscription.getRevision());
        entity.setStartedAt(toJdbc(subscription.getStartedAt()));
        entity.setEndsAt(toJdbc(subscription.getEndsAt()));
        entity.setUpdatedAt(toJdbc(subscription.getUpdatedAt()));
        return entity;
    }

    public Subscription toDomain() {
        return Subscription.restore(id, accountId, profile, planId, planType, status, renewalEnabled,
                allowedCapacity, revision, toInstant(startedAt), toInstant(endsAt), toInstant(updatedAt));
    }

    static OffsetDateTime toJdbc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
