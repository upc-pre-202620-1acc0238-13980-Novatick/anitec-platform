package com.anitec.backend.linking.infrastructure;

import com.anitec.backend.linking.domain.LinkingInvitation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@code linking_invitations}. */
public interface LinkingInvitationJpaRepository extends JpaRepository<LinkingInvitationJpaEntity, UUID> {

    Optional<LinkingInvitationJpaEntity> findByFarmerIdAndVetIdAndStatus(
            UUID farmerId, UUID vetId, LinkingInvitation.Status status);

    List<LinkingInvitationJpaEntity> findByVetIdAndStatusOrderBySentAtDesc(
            UUID vetId, LinkingInvitation.Status status);
}
