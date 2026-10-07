package com.anitec.backend.livestock.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Repository port for the Animal aggregate (report contract). */
public interface AnimalRepository {

    Animal save(Animal animal);

    Optional<Animal> findById(UUID animalId);

    /** Uniqueness of the code inside a farm, including deactivated animals; excludes the edited one. */
    boolean existsByFarmIdAndCode(UUID farmId, String code, UUID excludedAnimalId);

    /** Filtered inventory search restricted to the given owner scope (never unscoped). */
    List<Animal> search(Collection<UUID> ownerScope, UUID farmId, UUID speciesId, Animal.Status status, String query);

    long countActiveByOwners(Collection<UUID> ownerScope);

    long countBySpeciesId(UUID speciesId);

    long countByFarmId(UUID farmId);

    long countByOwnerId(UUID ownerId);
}
