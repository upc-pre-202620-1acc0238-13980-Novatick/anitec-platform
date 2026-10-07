package com.anitec.backend.livestock.domain;

import com.anitec.backend.shared.domain.DomainEvent;
import com.anitec.backend.shared.domain.DomainException;
import lombok.Getter;

import java.util.UUID;

/**
 * InventoryCapacity aggregate (report section 2.6.1): tracks the allowed and
 * current number of ACTIVE animals of a farmer. Limits come from
 * Subscriptions through PremiumActivated/PremiumExpired events carrying a
 * monotonic revision; stale revisions are ignored (decision #12).
 */
@Getter
public class InventoryCapacity {

    public record InventoryLimitUpdated(UUID ownerId, int allowedAnimals, long revision) implements DomainEvent {
    }

    private UUID ownerId;
    private int allowedAnimals;
    private int activeAnimals;
    private long lastPlanRevision;
    /**
     * Optimistic lock control column (JPA {@code @Version}); round-tripped on
     * every load/save so concurrent slot updates fail instead of overwriting.
     */
    private long version;

    protected InventoryCapacity() {
    }

    private InventoryCapacity(UUID ownerId, int allowedAnimals, int activeAnimals, long lastPlanRevision,
                              long version) {
        this.ownerId = ownerId;
        this.allowedAnimals = allowedAnimals;
        this.activeAnimals = activeAnimals;
        this.lastPlanRevision = lastPlanRevision;
        this.version = version;
    }

    public static InventoryCapacity create(UUID ownerId, int allowedAnimals) {
        return new InventoryCapacity(ownerId, allowedAnimals, 0, 0L, 0L);
    }

    public static InventoryCapacity restore(UUID ownerId, int allowedAnimals, int activeAnimals,
                                            long lastPlanRevision, long version) {
        return new InventoryCapacity(ownerId, allowedAnimals, activeAnimals, lastPlanRevision, version);
    }

    public boolean hasAvailableSlot() {
        return activeAnimals < allowedAnimals;
    }

    /** @throws DomainException INVENTORY_LIMIT_REACHED when the plan limit is met. */
    public void occupySlot() {
        if (!hasAvailableSlot()) {
            throw DomainException.business("INVENTORY_LIMIT_REACHED",
                    "Alcanzó el límite de animales activos de su plan; actualice su suscripción");
        }
        activeAnimals++;
    }

    /** Reduces the counter without ever going negative (idempotent, US05). */
    public void releaseSlot() {
        if (activeAnimals > 0) {
            activeAnimals--;
        }
    }

    /** Applies only strictly increasing revisions (TS02 semantics). */
    public boolean updateLimit(int newLimit, long revision) {
        if (revision <= lastPlanRevision || newLimit < 0) {
            return false;
        }
        this.allowedAnimals = newLimit;
        this.lastPlanRevision = revision;
        return true;
    }
}
