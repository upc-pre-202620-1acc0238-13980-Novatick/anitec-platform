package com.anitec.backend.livestock.infrastructure;

import com.anitec.backend.livestock.domain.InventoryCapacity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Persistence model for {@code livestock_inventory_capacity} (Flyway V2). */
@Entity
@Table(name = "livestock_inventory_capacity")
@Getter
@Setter
@NoArgsConstructor
public class InventoryCapacityJpaEntity {

    @Id
    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(name = "allowed_animals", nullable = false)
    private int allowedAnimals;

    @Column(name = "active_animals", nullable = false)
    private int activeAnimals;

    @Column(name = "last_plan_revision", nullable = false)
    private long lastPlanRevision;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public static InventoryCapacityJpaEntity fromDomain(InventoryCapacity capacity) {
        InventoryCapacityJpaEntity entity = new InventoryCapacityJpaEntity();
        entity.setOwnerId(capacity.getOwnerId());
        entity.setAllowedAnimals(capacity.getAllowedAnimals());
        entity.setActiveAnimals(capacity.getActiveAnimals());
        entity.setLastPlanRevision(capacity.getLastPlanRevision());
        entity.setVersion(capacity.getVersion());
        entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return entity;
    }

    public InventoryCapacity toDomain() {
        return InventoryCapacity.restore(ownerId, allowedAnimals, activeAnimals, lastPlanRevision,
                version == null ? 0L : version);
    }
}
