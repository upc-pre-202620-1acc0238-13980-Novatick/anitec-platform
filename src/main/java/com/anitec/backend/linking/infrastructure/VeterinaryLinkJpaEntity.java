package com.anitec.backend.linking.infrastructure;

import com.anitec.backend.linking.domain.VeterinaryLink;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Persistence model for {@code linking_links} (Flyway V3). */
@Entity
@Table(name = "linking_links")
@Getter
@Setter
@NoArgsConstructor
public class VeterinaryLinkJpaEntity {

    @Id
    private UUID id;

    @Column(name = "invitation_id")
    private UUID invitationId;

    @Column(name = "farmer_id", nullable = false)
    private UUID farmerId;

    @Column(name = "vet_id", nullable = false)
    private UUID vetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VeterinaryLink.Status status;

    @Column(name = "linked_at", nullable = false)
    private OffsetDateTime linkedAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    public static VeterinaryLinkJpaEntity fromDomain(VeterinaryLink link) {
        VeterinaryLinkJpaEntity entity = new VeterinaryLinkJpaEntity();
        entity.setId(link.getId());
        entity.setInvitationId(link.getInvitationId());
        entity.setFarmerId(link.getFarmerId());
        entity.setVetId(link.getVetId());
        entity.setStatus(link.getStatus());
        entity.setLinkedAt(LinkingInvitationJpaEntity.toJdbc(link.getLinkedAt()));
        entity.setRevokedAt(LinkingInvitationJpaEntity.toJdbc(link.getRevokedAt()));
        return entity;
    }

    public VeterinaryLink toDomain() {
        return VeterinaryLink.restore(id, invitationId, farmerId, vetId, status,
                LinkingInvitationJpaEntity.toInstant(linkedAt),
                LinkingInvitationJpaEntity.toInstant(revokedAt));
    }
}
