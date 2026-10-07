package com.anitec.backend.linking.infrastructure;

import com.anitec.backend.linking.domain.VeterinaryLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@code linking_links}. */
public interface VeterinaryLinkJpaRepository extends JpaRepository<VeterinaryLinkJpaEntity, UUID> {

    Optional<VeterinaryLinkJpaEntity> findByFarmerIdAndVetIdAndStatus(
            UUID farmerId, UUID vetId, VeterinaryLink.Status status);

    List<VeterinaryLinkJpaEntity> findByFarmerIdAndStatusOrderByLinkedAtDesc(
            UUID farmerId, VeterinaryLink.Status status);

    List<VeterinaryLinkJpaEntity> findByVetIdAndStatusOrderByLinkedAtDesc(
            UUID vetId, VeterinaryLink.Status status);

    long countByVetIdAndStatus(UUID vetId, VeterinaryLink.Status status);
}
