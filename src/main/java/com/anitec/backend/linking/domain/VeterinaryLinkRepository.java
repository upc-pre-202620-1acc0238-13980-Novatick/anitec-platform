package com.anitec.backend.linking.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Repository port for active veterinary links. */
public interface VeterinaryLinkRepository {

    VeterinaryLink save(VeterinaryLink link);

    Optional<VeterinaryLink> findById(UUID linkId);

    Optional<VeterinaryLink> findActiveByPair(UUID farmerId, UUID veterinarianId);

    boolean existsActive(UUID farmerId, UUID veterinarianId);

    List<VeterinaryLink> findActiveByFarmerId(UUID farmerId);

    List<VeterinaryLink> findActiveByVeterinarianId(UUID veterinarianId);

    long countActiveByVeterinarianId(UUID veterinarianId);
}
