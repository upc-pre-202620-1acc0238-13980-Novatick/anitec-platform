package com.anitec.backend.linking.domain;

import com.anitec.backend.shared.domain.DomainEvent;
import com.anitec.backend.shared.domain.DomainException;
import lombok.Getter;

import java.util.UUID;

/**
 * LinkingCapacity aggregate: maximum number of ACTIVE farmer links of a
 * veterinarian, driven by Subscriptions plan limits with monotonic revisions
 * (mirrors InventoryCapacity for the vet profile).
 */
@Getter
public class LinkingCapacity {

    public record LinkingLimitUpdated(UUID vetId, int allowedRanchers, long revision) implements DomainEvent {
    }

    private UUID vetId;
    private int allowedRanchers;
    private int activeLinks;
    private long lastPlanRevision;
    /**
     * Optimistic lock control column (JPA {@code @Version}); round-tripped on
     * every load/save so concurrent slot updates fail instead of overwriting.
     */
    private long version;

    protected LinkingCapacity() {
    }

    private LinkingCapacity(UUID vetId, int allowedRanchers, int activeLinks, long lastPlanRevision,
                            long version) {
        this.vetId = vetId;
        this.allowedRanchers = allowedRanchers;
        this.activeLinks = activeLinks;
        this.lastPlanRevision = lastPlanRevision;
        this.version = version;
    }

    public static LinkingCapacity create(UUID vetId, int allowedRanchers) {
        return new LinkingCapacity(vetId, allowedRanchers, 0, 0L, 0L);
    }

    public static LinkingCapacity restore(UUID vetId, int allowedRanchers, int activeLinks,
                                          long lastPlanRevision, long version) {
        return new LinkingCapacity(vetId, allowedRanchers, activeLinks, lastPlanRevision, version);
    }

    public boolean hasAvailableSlot() {
        return activeLinks < allowedRanchers;
    }

    /** @throws DomainException LINKING_LIMIT_REACHED when the plan limit is met (US15). */
    public void occupySlot() {
        if (!hasAvailableSlot()) {
            throw DomainException.business("LINKING_LIMIT_REACHED",
                    "Alcanzó el límite de ganaderos vinculados de su plan; actualice su suscripción");
        }
        activeLinks++;
    }

    public void releaseSlot() {
        if (activeLinks > 0) {
            activeLinks--;
        }
    }

    public boolean updateLimit(int newLimit, long revision) {
        if (revision <= lastPlanRevision || newLimit < 0) {
            return false;
        }
        this.allowedRanchers = newLimit;
        this.lastPlanRevision = revision;
        return true;
    }
}
