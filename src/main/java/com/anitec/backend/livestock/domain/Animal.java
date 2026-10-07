package com.anitec.backend.livestock.domain;

import com.anitec.backend.shared.domain.DomainEvent;
import com.anitec.backend.shared.domain.DomainException;
import lombok.Getter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Animal aggregate root (Livestock Management bounded context, report
 * section 2.6.1). Owns its data, status transitions and observations.
 * The server assigns owner, status and timestamps (spec section 6.2).
 */
@Getter
public class Animal {

    public enum Sex {MACHO, HEMBRA}

    public enum Status {ACTIVO, INACTIVO, VENDIDO, FALLECIDO}

    public record AnimalRegistered(UUID animalId, UUID ownerId, UUID farmId) implements DomainEvent {
    }

    public record AnimalDeactivated(UUID animalId, UUID ownerId) implements DomainEvent {
    }

    public record ObservationRegistered(UUID animalId, UUID authorId) implements DomainEvent {
    }

    private UUID id;
    private UUID farmId;
    private UUID ownerId;
    private UUID speciesId;
    private AnimalCode code;
    private String name;
    private String breed;
    private Sex sex;
    private LocalDate birthDate;
    private Status status;
    private String photoUrl;
    private Instant createdAt;
    private Instant updatedAt;
    private List<AnimalObservation> observations = new ArrayList<>();

    protected Animal() {
    }

    // ------------------------------------------------------------- factories

    public static Animal register(UUID farmId, UUID ownerId, String code, UUID speciesId, Sex sex,
                                  String name, String breed, LocalDate birthDate, String photoUrl, Instant now) {
        requireValidBirthDate(birthDate);
        Animal animal = new Animal();
        animal.id = UUID.randomUUID();
        animal.farmId = farmId;
        animal.ownerId = ownerId;
        animal.speciesId = speciesId;
        animal.code = AnimalCode.of(code);
        animal.sex = sex;
        animal.name = blankToNull(name);
        animal.breed = blankToNull(breed);
        animal.birthDate = birthDate;
        animal.photoUrl = blankToNull(photoUrl);
        animal.status = Status.ACTIVO;
        animal.createdAt = now;
        animal.updatedAt = now;
        return animal;
    }

    public static Animal restore(UUID id, UUID farmId, UUID ownerId, UUID speciesId, String code,
                                 String name, String breed, Sex sex, LocalDate birthDate, Status status,
                                 String photoUrl, Instant createdAt, Instant updatedAt,
                                 List<AnimalObservation> observations) {
        Animal animal = new Animal();
        animal.id = id;
        animal.farmId = farmId;
        animal.ownerId = ownerId;
        animal.speciesId = speciesId;
        animal.code = AnimalCode.of(code);
        animal.name = name;
        animal.breed = breed;
        animal.sex = sex;
        animal.birthDate = birthDate;
        animal.status = status;
        animal.photoUrl = photoUrl;
        animal.createdAt = createdAt;
        animal.updatedAt = updatedAt;
        animal.observations = observations == null ? new ArrayList<>() : new ArrayList<>(observations);
        return animal;
    }

    // ----------------------------------------------------------- operations

    /** Updates the editable fields, never id/owner/status/observations (US04). */
    public void updateDetails(String code, UUID speciesId, Sex sex, String name, String breed,
                              LocalDate birthDate, String photoUrl, Instant now) {
        requireValidBirthDate(birthDate);
        this.code = AnimalCode.of(code);
        this.speciesId = speciesId;
        this.sex = sex;
        this.name = blankToNull(name);
        this.breed = blankToNull(breed);
        this.birthDate = birthDate;
        this.photoUrl = blankToNull(photoUrl);
        this.updatedAt = now;
    }

    /** @return true only when the state actually changed (idempotent, US05). */
    public boolean changeStatus(Status newStatus, Instant now) {
        if (this.status == newStatus) {
            return false;
        }
        this.status = newStatus;
        this.updatedAt = now;
        return true;
    }

    public AnimalObservation addObservation(String text, UUID authorId, Instant now) {
        AnimalObservation observation = AnimalObservation.create(text, authorId, now);
        observations.add(observation);
        updatedAt = now;
        return observation;
    }

    public boolean belongsTo(UUID ownerId) {
        return this.ownerId != null && this.ownerId.equals(ownerId);
    }

    public boolean isActive() {
        return status == Status.ACTIVO;
    }

    public String codeValue() {
        return code == null ? null : code.getValue();
    }

    // ------------------------------------------------------------- helpers

    private static void requireValidBirthDate(LocalDate birthDate) {
        if (birthDate != null && birthDate.isAfter(LocalDate.now(ZoneOffset.UTC))) {
            throw DomainException.badRequest("INVALID_DATE", "birthDate: la fecha de nacimiento no puede ser futura");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
