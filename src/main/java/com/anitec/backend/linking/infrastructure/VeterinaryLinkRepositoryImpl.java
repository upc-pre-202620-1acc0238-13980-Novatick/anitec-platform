package com.anitec.backend.linking.infrastructure;

import com.anitec.backend.linking.domain.VeterinaryLink;
import com.anitec.backend.linking.domain.VeterinaryLinkRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Infrastructure adapter for {@link VeterinaryLinkRepository}. */
@Component
public class VeterinaryLinkRepositoryImpl implements VeterinaryLinkRepository {

    private final VeterinaryLinkJpaRepository jpa;

    public VeterinaryLinkRepositoryImpl(VeterinaryLinkJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public VeterinaryLink save(VeterinaryLink link) {
        return jpa.save(VeterinaryLinkJpaEntity.fromDomain(link)).toDomain();
    }

    @Override
    public Optional<VeterinaryLink> findById(UUID linkId) {
        return jpa.findById(linkId).map(VeterinaryLinkJpaEntity::toDomain);
    }

    @Override
    public Optional<VeterinaryLink> findActiveByPair(UUID farmerId, UUID veterinarianId) {
        return jpa.findByFarmerIdAndVetIdAndStatus(farmerId, veterinarianId, VeterinaryLink.Status.ACTIVA)
                .map(VeterinaryLinkJpaEntity::toDomain);
    }

    @Override
    public boolean existsActive(UUID farmerId, UUID veterinarianId) {
        return jpa.findByFarmerIdAndVetIdAndStatus(farmerId, veterinarianId, VeterinaryLink.Status.ACTIVA)
                .isPresent();
    }

    @Override
    public List<VeterinaryLink> findActiveByFarmerId(UUID farmerId) {
        return jpa.findByFarmerIdAndStatusOrderByLinkedAtDesc(farmerId, VeterinaryLink.Status.ACTIVA)
                .stream().map(VeterinaryLinkJpaEntity::toDomain).toList();
    }

    @Override
    public List<VeterinaryLink> findActiveByVeterinarianId(UUID veterinarianId) {
        return jpa.findByVetIdAndStatusOrderByLinkedAtDesc(veterinarianId, VeterinaryLink.Status.ACTIVA)
                .stream().map(VeterinaryLinkJpaEntity::toDomain).toList();
    }

    @Override
    public long countActiveByVeterinarianId(UUID veterinarianId) {
        return jpa.countByVetIdAndStatus(veterinarianId, VeterinaryLink.Status.ACTIVA);
    }
}
