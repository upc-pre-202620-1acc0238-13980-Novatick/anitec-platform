package com.anitec.backend.livestock.infrastructure;

import com.anitec.backend.livestock.domain.InventoryCapacity;
import com.anitec.backend.livestock.domain.InventoryCapacityRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** Infrastructure adapter for {@link InventoryCapacityRepository}. */
@Component
public class InventoryCapacityRepositoryImpl implements InventoryCapacityRepository {

    private final InventoryCapacityJpaRepository jpa;

    public InventoryCapacityRepositoryImpl(InventoryCapacityJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public InventoryCapacity save(InventoryCapacity capacity) {
        return jpa.save(InventoryCapacityJpaEntity.fromDomain(capacity)).toDomain();
    }

    @Override
    public Optional<InventoryCapacity> findByOwnerId(UUID ownerId) {
        return jpa.findByOwnerId(ownerId).map(InventoryCapacityJpaEntity::toDomain);
    }
}
