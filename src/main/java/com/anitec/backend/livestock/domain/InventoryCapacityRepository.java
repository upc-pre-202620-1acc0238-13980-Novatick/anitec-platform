package com.anitec.backend.livestock.domain;

import java.util.Optional;
import java.util.UUID;

/** Repository port for the InventoryCapacity aggregate. */
public interface InventoryCapacityRepository {

    InventoryCapacity save(InventoryCapacity capacity);

    Optional<InventoryCapacity> findByOwnerId(UUID ownerId);
}
