package com.anitec.backend.livestock.application;

import com.anitec.backend.livestock.domain.Animal;
import com.anitec.backend.livestock.domain.AnimalRepository;
import com.anitec.backend.livestock.domain.Farm;
import com.anitec.backend.livestock.domain.FarmRepository;
import com.anitec.backend.livestock.domain.InventoryCapacity;
import com.anitec.backend.livestock.domain.InventoryCapacityRepository;
import com.anitec.backend.livestock.domain.Species;
import com.anitec.backend.livestock.domain.SpeciesRepository;
import com.anitec.backend.shared.domain.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Livestock read model (report class LivestockQueries). All reads are scoped
 * to an owner collection so cross-tenant data can never leak (section 9.1).
 */
@Service
@Transactional(readOnly = true)
public class LivestockQueries {

    private final AnimalRepository animals;
    private final FarmRepository farms;
    private final SpeciesRepository species;
    private final InventoryCapacityRepository capacities;

    public LivestockQueries(AnimalRepository animals, FarmRepository farms, SpeciesRepository species,
                            InventoryCapacityRepository capacities) {
        this.animals = animals;
        this.farms = farms;
        this.species = species;
        this.capacities = capacities;
    }

    // ------------------------------------------------------------- records

    public record AnimalListItem(UUID id, String code, String name, String species, UUID speciesId,
                                 Animal.Status status, UUID farmId, UUID ownerId) {
    }

    public record SpeciesView(UUID id, String name, String description, String photoUrl) {
        static SpeciesView from(Species value) {
            return new SpeciesView(value.getId(), value.getName(), value.getDescription(), value.getPhotoUrl());
        }
    }

    // ------------------------------------------------------------- queries

    public List<AnimalListItem> search(Collection<UUID> ownerScope, UUID farmId, UUID speciesId,
                                       Animal.Status status, String query) {
        if (ownerScope == null || ownerScope.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> speciesNames = species.findAll().stream()
                .collect(Collectors.toMap(Species::getId, Species::getName, (a, b) -> a));
        return animals.search(ownerScope, farmId, speciesId, status, query).stream()
                .map(animal -> new AnimalListItem(animal.getId(), animal.codeValue(), animal.getName(),
                        speciesNames.get(animal.getSpeciesId()), animal.getSpeciesId(),
                        animal.getStatus(), animal.getFarmId(), animal.getOwnerId()))
                .toList();
    }

    public long countActive(Collection<UUID> ownerScope) {
        if (ownerScope == null || ownerScope.isEmpty()) {
            return 0;
        }
        return animals.countActiveByOwners(ownerScope);
    }

    /** Returns the animal when its owner is inside the caller's scope; 403 otherwise. */
    public Animal requireAnimalInScope(UUID animalId, Collection<UUID> ownerScope) {
        Animal animal = animals.findById(animalId)
                .orElseThrow(() -> DomainException.notFound("Animal no encontrado"));
        if (ownerScope == null || !ownerScope.contains(animal.getOwnerId())) {
            throw DomainException.forbidden("No tiene autorización sobre este animal");
        }
        return animal;
    }

    public Optional<Animal> findAnimal(UUID animalId) {
        return animals.findById(animalId);
    }

    public Farm requireFarmInScope(UUID farmId, Collection<UUID> ownerScope) {
        Farm farm = farms.findById(farmId)
                .orElseThrow(() -> DomainException.notFound("Finca no encontrada"));
        if (ownerScope == null || !ownerScope.contains(farm.getOwnerId())) {
            throw DomainException.forbidden("No tiene autorización sobre esta finca");
        }
        return farm;
    }

    public List<Farm> farmsOf(UUID ownerId) {
        return farms.findByOwnerId(ownerId);
    }

    public String farmName(UUID farmId) {
        return farms.findById(farmId).map(Farm::getName).orElse(null);
    }

    public List<SpeciesView> listSpecies() {
        return species.findAll().stream().map(SpeciesView::from).toList();
    }

    public InventoryCapacity capacityOf(UUID ownerId) {
        return capacities.findByOwnerId(ownerId)
                .orElseThrow(() -> DomainException.notFound("Capacidad de inventario no encontrada"));
    }

    public long countAnimalsByOwner(UUID ownerId) {
        return animals.countByOwnerId(ownerId);
    }

    public long countAnimalsByFarm(UUID farmId) {
        return animals.countByFarmId(farmId);
    }

    /** Convenience for other contexts: species name lookup. */
    public String speciesName(UUID speciesId) {
        if (speciesId == null) {
            return null;
        }
        return species.findById(speciesId).map(Species::getName).orElse(null);
    }
}
