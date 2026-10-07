package com.anitec.backend.linking.infrastructure;

import com.anitec.backend.linking.domain.InvitationRepository;
import com.anitec.backend.linking.domain.LinkingInvitation;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Infrastructure adapter for {@link InvitationRepository}. */
@Component
public class InvitationRepositoryImpl implements InvitationRepository {

    private final LinkingInvitationJpaRepository jpa;

    public InvitationRepositoryImpl(LinkingInvitationJpaRepository jpa) {
        this.jpa = jpa;
    }

    /**
     * Writes through and flushes immediately: the expiry transition of an old
     * pending invitation must reach the database before a replacement row is
     * inserted, otherwise Hibernate's flush order (inserts before updates)
     * would let the new row lose against {@code uq_invitation_pending}.
     */
    @Override
    public LinkingInvitation save(LinkingInvitation invitation) {
        return jpa.saveAndFlush(LinkingInvitationJpaEntity.fromDomain(invitation)).toDomain();
    }

    @Override
    public Optional<LinkingInvitation> findById(UUID invitationId) {
        return jpa.findById(invitationId).map(LinkingInvitationJpaEntity::toDomain);
    }

    @Override
    public List<LinkingInvitation> findPendingByVeterinarianId(UUID veterinarianId) {
        return jpa.findByVetIdAndStatusOrderBySentAtDesc(veterinarianId, LinkingInvitation.Status.PENDIENTE)
                .stream().map(LinkingInvitationJpaEntity::toDomain).toList();
    }

    @Override
    public Optional<LinkingInvitation> findPendingByPair(UUID farmerId, UUID veterinarianId) {
        return jpa.findByFarmerIdAndVetIdAndStatus(farmerId, veterinarianId,
                LinkingInvitation.Status.PENDIENTE).map(LinkingInvitationJpaEntity::toDomain);
    }
}
