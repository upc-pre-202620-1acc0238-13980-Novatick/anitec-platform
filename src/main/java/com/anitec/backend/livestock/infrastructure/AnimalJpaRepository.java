package com.anitec.backend.livestock.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

/** Spring Data repository for {@code livestock_animals}. */
public interface AnimalJpaRepository extends JpaRepository<AnimalJpaEntity, UUID>,
        JpaSpecificationExecutor<AnimalJpaEntity> {

    boolean existsByFarmIdAndCode(UUID farmId, String code);

    boolean existsByFarmIdAndCodeAndIdNot(UUID farmId, String code, UUID excludedAnimalId);

    long countBySpeciesId(UUID speciesId);

    long countByFarmId(UUID farmId);

    long countByOwnerId(UUID ownerId);
}
