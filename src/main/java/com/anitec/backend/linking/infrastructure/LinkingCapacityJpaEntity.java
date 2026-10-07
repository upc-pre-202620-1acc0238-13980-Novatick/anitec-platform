package com.anitec.backend.linking.infrastructure;

import com.anitec.backend.linking.domain.LinkingCapacity;
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

/** Persistence model for {@code linking_capacity} (Flyway V3). */
@Entity
@Table(name = "linking_capacity")
@Getter
@Setter
@NoArgsConstructor
public class LinkingCapacityJpaEntity {

    @Id
    @Column(name = "vet_id")
    private UUID vetId;

    @Column(name = "allowed_ranchers", nullable = false)
    private int allowedRanchers;

    @Column(name = "active_links", nullable = false)
    private int activeLinks;

    @Column(name = "last_plan_revision", nullable = false)
    private long lastPlanRevision;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public static LinkingCapacityJpaEntity fromDomain(LinkingCapacity capacity) {
        LinkingCapacityJpaEntity entity = new LinkingCapacityJpaEntity();
        entity.setVetId(capacity.getVetId());
        entity.setAllowedRanchers(capacity.getAllowedRanchers());
        entity.setActiveLinks(capacity.getActiveLinks());
        entity.setLastPlanRevision(capacity.getLastPlanRevision());
        entity.setVersion(capacity.getVersion());
        entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return entity;
    }

    public LinkingCapacity toDomain() {
        return LinkingCapacity.restore(vetId, allowedRanchers, activeLinks, lastPlanRevision,
                version == null ? 0L : version);
    }
}
