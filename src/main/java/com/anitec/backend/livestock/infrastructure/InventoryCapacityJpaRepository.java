package com.anitec.backend.livestock.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@code livestock_inventory_capacity}. */
public interface InventoryCapacityJpaRepository extends JpaRepository<InventoryCapacityJpaEntity, UUID> {

    Optional<InventoryCapacityJpaEntity> findByOwnerId(UUID ownerId);
}
