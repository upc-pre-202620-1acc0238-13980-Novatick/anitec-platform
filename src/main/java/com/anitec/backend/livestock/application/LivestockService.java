package com.anitec.backend.livestock.application;

import com.anitec.backend.livestock.domain.Animal;
import com.anitec.backend.livestock.domain.AnimalObservation;
import com.anitec.backend.livestock.domain.AnimalRepository;
import com.anitec.backend.livestock.domain.Farm;
import com.anitec.backend.livestock.domain.FarmRepository;
import com.anitec.backend.livestock.domain.InventoryCapacity;
import com.anitec.backend.livestock.domain.InventoryCapacityRepository;
import com.anitec.backend.livestock.domain.Species;
import com.anitec.backend.livestock.domain.SpeciesRepository;
import com.anitec.backend.shared.application.DomainEventDispatcher;
import com.anitec.backend.shared.domain.DomainException;
import com.anitec.backend.shared.domain.Role;
import com.anitec.backend.subscriptions.domain.Subscription;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Livestock command service (report class LivestockService). Coordinates the
 * Animal/Farm/Species/InventoryCapacity aggregates, capacity slots and the
 * plan-limit events published by Subscriptions (decision #12: in-process
 * domain events with increasing revisions).
 */
@Service
public class LivestockService {

    private final FarmRepository farms;
    private final AnimalRepository animals;
    private final SpeciesRepository species;
    private final InventoryCapacityRepository capacities;
    private final DomainEventDispatcher events;

    public LivestockService(FarmRepository farms, AnimalRepository animals, SpeciesRepository species,
                            InventoryCapacityRepository capacities, DomainEventDispatcher events) {
        this.farms = farms;
        this.animals = animals;
        this.species = species;
        this.capacities = capacities;
        this.events = events;
    }

    // ---------------------------------------------------------------- farms

    @Transactional
    public Farm createFarm(UUID callerId, String name, String location) {
        return farms.save(Farm.create(callerId, name, location, Instant.now()));
    }

    // -------------------------------------------------------------- animals

    @Transactional
    public Animal registerAnimal(UUID callerId, UUID farmId, String code, UUID speciesId, Animal.Sex sex,
                                 String name, String breed, LocalDate birthDate, String photoUrl) {
        Farm farm = requireOwnedFarm(callerId, farmId);
        requireSpecies(speciesId);
        requireCodeAvailable(farm.getId(), code, null);

        InventoryCapacity capacity = requireCapacity(callerId);
        capacity.occupySlot(); // throws INVENTORY_LIMIT_REACHED when the plan limit is met (US01)

        Animal animal = Animal.register(farm.getId(), callerId, code, speciesId, sex,
                name, breed, birthDate, photoUrl, Instant.now());
        animals.save(animal);
        capacities.save(capacity);
        events.publish(new Animal.AnimalRegistered(animal.getId(), callerId, farm.getId()));
        return animal;
    }

    @Transactional
    public Animal updateAnimal(UUID callerId, UUID animalId, String code, UUID speciesId, Animal.Sex sex,
                               String name, String breed, LocalDate birthDate, String photoUrl) {
        Animal animal = requireOwnedAnimal(callerId, animalId);
        requireSpecies(speciesId);
        requireCodeAvailable(animal.getFarmId(), code, animal.getId());
        animal.updateDetails(code, speciesId, sex, name, breed, birthDate, photoUrl, Instant.now());
        return animals.save(animal);
    }

    /** Soft deactivation (US05): releases exactly one slot, idempotent. */
    @Transactional
    public Animal deactivateAnimal(UUID callerId, UUID animalId) {
        Animal animal = requireOwnedAnimal(callerId, animalId);
        if (!animal.isActive()) {
            return animal; // repeated request: no additional slot release
        }
        animal.changeStatus(Animal.Status.INACTIVO, Instant.now());
        InventoryCapacity capacity = requireCapacity(callerId);
        capacity.releaseSlot();
        animals.save(animal);
        capacities.save(capacity);
        events.publish(new Animal.AnimalDeactivated(animal.getId(), callerId));
        return animal;
    }

    @Transactional
    public Animal changeAnimalStatus(UUID callerId, UUID animalId, Animal.Status newStatus) {
        Animal animal = requireOwnedAnimal(callerId, animalId);
        if (animal.getStatus() == newStatus) {
            return animal; // idempotent
        }
        InventoryCapacity capacity = requireCapacity(callerId);
        boolean wasActive = animal.isActive();
        boolean becomesActive = newStatus == Animal.Status.ACTIVO;

        if (wasActive && !becomesActive) {
            capacity.releaseSlot();
        } else if (!wasActive && becomesActive) {
            capacity.occupySlot(); // reactivation requires an available slot
        }
        animal.changeStatus(newStatus, Instant.now());
        animals.save(animal);
        capacities.save(capacity);
        if (wasActive && !becomesActive) {
            events.publish(new Animal.AnimalDeactivated(animal.getId(), callerId));
        }
        return animal;
    }

    @Transactional
    public AnimalObservation addObservation(UUID callerId, UUID animalId, String text) {
        Animal animal = requireOwnedAnimal(callerId, animalId); // only the farmer authors remarks
        AnimalObservation observation = animal.addObservation(text, callerId, Instant.now());
        animals.save(animal);
        events.publish(new Animal.ObservationRegistered(animal.getId(), callerId));
        return observation;
    }

    // -------------------------------------------------------- species admin

    @Transactional
    public Species createSpecies(String name, String description, String photoUrl) {
        return species.save(Species.create(name, description, photoUrl, Instant.now()));
    }

    @Transactional
    public Species updateSpecies(UUID speciesId, String name, String description, String photoUrl) {
        Species found = species.findById(speciesId)
                .orElseThrow(() -> DomainException.notFound("Especie no encontrada"));
        found.update(name, description, photoUrl);
        return species.save(found);
    }

    @Transactional
    public void deleteSpecies(UUID speciesId) {
        species.findById(speciesId)
                .orElseThrow(() -> DomainException.notFound("Especie no encontrada"));
        if (animals.countBySpeciesId(speciesId) > 0) {
            throw DomainException.conflict("SPECIES_IN_USE", "La especie está en uso por al menos un animal");
        }
        species.delete(speciesId);
    }

    // ------------------------------------- subscriptions -> capacity limits

    @EventListener
    @Transactional
    public void onPremiumActivated(Subscription.PremiumActivated event) {
        if (event.profile() == Role.GANADERO) {
            applyLimit(event.accountId(), event.limit(), event.revision());
        }
    }

    @EventListener
    @Transactional
    public void onSubscriptionLimitChanged(Subscription.SubscriptionLimitChanged event) {
        if (event.profile() == Role.GANADERO) {
            applyLimit(event.accountId(), event.limit(), event.revision());
        }
    }

    @EventListener
    @Transactional
    public void onPremiumExpired(Subscription.PremiumExpired event) {
        if (event.profile() != Role.GANADERO) {
            return;
        }
        applyLimit(event.accountId(), event.limit(), event.revision());
    }

    private void applyLimit(UUID ownerId, int limit, long revision) {
        InventoryCapacity capacity = capacities.findByOwnerId(ownerId)
                .orElseGet(() -> InventoryCapacity.create(ownerId, 0));
        if (capacity.updateLimit(limit, revision)) {
            capacities.save(capacity);
            events.publish(new InventoryCapacity.InventoryLimitUpdated(ownerId, limit, revision));
        }
    }

    // -------------------------------------------------------------- helpers

    private Farm requireOwnedFarm(UUID callerId, UUID farmId) {
        Farm farm = farms.findById(farmId)
                .orElseThrow(() -> DomainException.notFound("Finca no encontrada"));
        if (!farm.belongsTo(callerId)) {
            throw DomainException.forbidden("La finca no pertenece a su cuenta");
        }
        return farm;
    }

    private Animal requireOwnedAnimal(UUID callerId, UUID animalId) {
        Animal animal = animals.findById(animalId)
                .orElseThrow(() -> DomainException.notFound("Animal no encontrado"));
        if (!animal.belongsTo(callerId)) {
            throw DomainException.forbidden("El animal no pertenece a su cuenta");
        }
        return animal;
    }

    private void requireSpecies(UUID speciesId) {
        if (speciesId == null || species.findById(speciesId).isEmpty()) {
            throw DomainException.badRequest("VALIDATION_ERROR", "speciesId: la especie indicada no existe");
        }
    }

    private void requireCodeAvailable(UUID farmId, String code, UUID excludedAnimalId) {
        if (animals.existsByFarmIdAndCode(farmId, code, excludedAnimalId)) {
            throw DomainException.conflict("DUPLICATE_ANIMAL_CODE",
                    "Ya existe un animal con ese código en la finca");
        }
    }

    InventoryCapacity requireCapacity(UUID ownerId) {
        return capacities.findByOwnerId(ownerId)
                .orElseThrow(() -> DomainException.internal(
                        "Capacidad de inventario no inicializada para el propietario " + ownerId));
    }
}
