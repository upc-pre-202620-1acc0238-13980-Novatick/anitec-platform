package com.anitec.backend.linking.domain;

import java.util.Optional;
import java.util.UUID;

/** Repository port for the LinkingCapacity aggregate. */
public interface LinkingCapacityRepository {

    LinkingCapacity save(LinkingCapacity capacity);

    Optional<LinkingCapacity> findByVeterinarianId(UUID veterinarianId);
}
