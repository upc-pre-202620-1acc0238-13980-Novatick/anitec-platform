package com.anitec.backend.subscriptions.domain;

import com.anitec.backend.shared.domain.DomainEvent;
import com.anitec.backend.shared.domain.Role;
import lombok.Getter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Subscription aggregate (report class Subscription): plan state, vigency and
 * the monotonic revision handed to Livestock/Linking capacity updates
 * (TS02). A valid initial payment activates premium; cancelling renewal keeps
 * benefits until {@code endsAt}; expiry applies the free limit without
 * deleting animals or links (US22-US24).
 */
@Getter
public class Subscription {

    public enum Status {ACTIVA, VENCIDA, CANCELADA}

    public record PremiumActivated(UUID accountId, Role profile, int limit, long revision)
            implements DomainEvent {
    }

    /** Free-plan grant / admin limit edit: capacity only, no "premium" wording. */
    public record SubscriptionLimitChanged(UUID accountId, Role profile, int limit, long revision)
            implements DomainEvent {
    }

    public record PremiumExpired(UUID accountId, Role profile, int limit, long revision)
            implements DomainEvent {
    }

    public record RenewalCancelled(UUID accountId) implements DomainEvent {
    }

    private UUID id;
    private UUID accountId;
    private Role profile;
    private UUID planId;
    private Plan.PlanType planType;
    private Status status;
    private boolean renewalEnabled;
    private int allowedCapacity;
    private long revision;
    private Instant startedAt;
    private Instant endsAt;
    private Instant updatedAt;

    protected Subscription() {
    }

    private Subscription(UUID id, UUID accountId, Role profile, UUID planId, Plan.PlanType planType,
                         Status status, boolean renewalEnabled, int allowedCapacity, long revision,
                         Instant startedAt, Instant endsAt, Instant updatedAt) {
        this.id = id;
        this.accountId = accountId;
        this.profile = profile;
        this.planId = planId;
        this.planType = planType;
        this.status = status;
        this.renewalEnabled = renewalEnabled;
        this.allowedCapacity = allowedCapacity;
        this.revision = revision;
        this.startedAt = startedAt;
        this.endsAt = endsAt;
        this.updatedAt = updatedAt;
    }

    /** Free plan granted at registration (revision 1 drives capacity creation). */
    public static Subscription createForAccount(UUID accountId, Role profile, Plan freePlan, Instant now) {
        return new Subscription(UUID.randomUUID(), accountId, profile, freePlan.getId(),
                freePlan.getType(), Status.ACTIVA, true, freePlan.getCapacityLimit(), 1L, now, null, now);
    }

    public static Subscription restore(UUID id, UUID accountId, Role profile, UUID planId,
                                       Plan.PlanType planType, Status status, boolean renewalEnabled,
                                       int allowedCapacity, long revision, Instant startedAt,
                                       Instant endsAt, Instant updatedAt) {
        return new Subscription(id, accountId, profile, planId, planType, status, renewalEnabled,
                allowedCapacity, revision, startedAt, endsAt, updatedAt);
    }

    /** US23: a confirmed payment activates premium and bumps the revision. */
    public void activatePremium(Plan premiumPlan, Instant now) {
        this.planId = premiumPlan.getId();
        this.planType = Plan.PlanType.PREMIUM;
        this.status = Status.ACTIVA;
        this.renewalEnabled = true;
        this.allowedCapacity = premiumPlan.getCapacityLimit();
        this.revision++;
        this.startedAt = now;
        this.endsAt = now.atOffset(java.time.ZoneOffset.UTC).toLocalDateTime()
                .plusMonths(1).atZone(java.time.ZoneId.of("UTC")).toInstant();
        this.updatedAt = now;
    }

    /** US24: idempotent; keeps premium until endsAt. */
    public boolean cancelRenewal() {
        if (!renewalEnabled) {
            return false;
        }
        renewalEnabled = false;
        return true;
    }

    /** TS02: apply the free limit when the paid period ends without renewal. */
    public void expire(Plan freePlan, Instant now) {
        this.planId = freePlan.getId();
        this.planType = Plan.PlanType.GRATUITO;
        this.status = Status.VENCIDA;
        this.renewalEnabled = false;
        this.allowedCapacity = freePlan.getCapacityLimit();
        this.revision++;
        this.endsAt = null;
        this.updatedAt = now;
    }

    /** Simulated renewal used by the fake gateway: extend one month, keep revision. */
    public void extendPeriod(Instant now) {
        this.endsAt = now.atOffset(java.time.ZoneOffset.UTC).toLocalDateTime()
                .plusMonths(1).atZone(java.time.ZoneId.of("UTC")).toInstant();
        this.updatedAt = now;
    }

    /** Applies a plan capacity change with a fresh revision (admin plan edit). */
    public void applyCapacity(int newLimit, Instant now) {
        this.allowedCapacity = newLimit;
        this.revision++;
        this.updatedAt = now;
    }

    public boolean isPremium() {
        return planType == Plan.PlanType.PREMIUM && status == Status.ACTIVA;
    }

    public boolean belongsTo(UUID accountId) {
        return this.accountId != null && this.accountId.equals(accountId);
    }

    public boolean isDue(Instant now) {
        return status == Status.ACTIVA && planType == Plan.PlanType.PREMIUM
                && endsAt != null && now.isAfter(endsAt);
    }

    public long monthsLeft(Instant now) {
        if (endsAt == null) {
            return 0;
        }
        return Math.max(0, ChronoUnit.DAYS.between(now, endsAt) / 30);
    }
}
