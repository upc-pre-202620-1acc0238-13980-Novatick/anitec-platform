package com.anitec.backend.care.domain;

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
 * CareRecord aggregate (report class CareRecord): the clinical attention of an
 * animal with its optional complementary records (treatments, vaccinations)
 * and versioned care instructions (US09-US11, US19-US20). Every write requires
 * an active vet-owner link, checked by the application layer.
 */
@Getter
public class CareRecord {

    public record AttentionRegistered(UUID careRecordId, UUID animalId, UUID ownerId, UUID veterinarianId,
                                      String title) implements DomainEvent {
    }

    public record InstructionsRegistered(UUID careRecordId, UUID animalId, UUID ownerId, UUID veterinarianId)
            implements DomainEvent {
    }

    public record InstructionsUpdated(UUID careRecordId, UUID animalId, UUID ownerId, UUID veterinarianId)
            implements DomainEvent {
    }

    // --------------------------------------------------------- child records

    @Getter
    public static class CareTreatment {
        private UUID id;
        private String description;
        private LocalDate appliedDate;

        protected CareTreatment() {
        }

        private CareTreatment(UUID id, String description, LocalDate appliedDate) {
            this.id = id;
            this.description = description;
            this.appliedDate = appliedDate;
        }

        public static CareTreatment create(String description, LocalDate appliedDate) {
            requireNonBlank(description, "description");
            requireNotFuture(appliedDate, "date");
            return new CareTreatment(UUID.randomUUID(), description.trim(), appliedDate);
        }

        public static CareTreatment restore(UUID id, String description, LocalDate appliedDate) {
            return new CareTreatment(id, description, appliedDate);
        }
    }

    @Getter
    public static class CareVaccination {
        private UUID id;
        private String name;
        private LocalDate appliedDate;

        protected CareVaccination() {
        }

        private CareVaccination(UUID id, String name, LocalDate appliedDate) {
            this.id = id;
            this.name = name;
            this.appliedDate = appliedDate;
        }

        public static CareVaccination create(String name, LocalDate appliedDate) {
            requireNonBlank(name, "name");
            requireNotFuture(appliedDate, "date");
            return new CareVaccination(UUID.randomUUID(), name.trim(), appliedDate);
        }

        public static CareVaccination restore(UUID id, String name, LocalDate appliedDate) {
            return new CareVaccination(id, name, appliedDate);
        }
    }

    @Getter
    public static class CareInstructionVersion {
        private UUID id;
        private String content;
        private UUID authorId;
        private Instant createdAt;

        protected CareInstructionVersion() {
        }

        private CareInstructionVersion(UUID id, String content, UUID authorId, Instant createdAt) {
            this.id = id;
            this.content = content;
            this.authorId = authorId;
            this.createdAt = createdAt;
        }

        static CareInstructionVersion create(String content, UUID authorId, Instant now) {
            requireNonBlank(content, "content");
            return new CareInstructionVersion(UUID.randomUUID(), content.trim(), authorId, now);
        }

        public static CareInstructionVersion restore(UUID id, String content, UUID authorId, Instant createdAt) {
            return new CareInstructionVersion(id, content, authorId, createdAt);
        }
    }

    // ------------------------------------------------------------ aggregate

    private UUID id;
    private UUID animalId;
    private UUID ownerId;
    private UUID veterinarianId;
    private UUID appointmentId;
    private String title;
    private String description;
    private LocalDate attentionDate;
    private Instant registeredAt;

    private List<CareTreatment> treatments = new ArrayList<>();
    private List<CareVaccination> vaccinations = new ArrayList<>();
    private List<CareInstructionVersion> instructionVersions = new ArrayList<>();

    protected CareRecord() {
    }

    public static CareRecord register(UUID animalId, UUID ownerId, UUID veterinarianId, UUID appointmentId,
                                      String title, String description, LocalDate attentionDate, Instant now) {
        requireNonBlank(title, "title");
        requireNotFuture(attentionDate, "attentionDate");
        CareRecord record = new CareRecord();
        record.id = UUID.randomUUID();
        record.animalId = animalId;
        record.ownerId = ownerId;
        record.veterinarianId = veterinarianId;
        record.appointmentId = appointmentId;
        record.title = title.trim();
        record.description = description;
        record.attentionDate = attentionDate == null
                ? LocalDate.now(ZoneOffset.UTC) : attentionDate;
        record.registeredAt = now;
        return record;
    }

    public static CareRecord restore(UUID id, UUID animalId, UUID ownerId, UUID veterinarianId,
                                     UUID appointmentId, String title, String description,
                                     LocalDate attentionDate, Instant registeredAt,
                                     List<CareTreatment> treatments, List<CareVaccination> vaccinations,
                                     List<CareInstructionVersion> instructionVersions) {
        CareRecord record = new CareRecord();
        record.id = id;
        record.animalId = animalId;
        record.ownerId = ownerId;
        record.veterinarianId = veterinarianId;
        record.appointmentId = appointmentId;
        record.title = title;
        record.description = description;
        record.attentionDate = attentionDate;
        record.registeredAt = registeredAt;
        record.treatments = treatments == null ? new ArrayList<>() : new ArrayList<>(treatments);
        record.vaccinations = vaccinations == null ? new ArrayList<>() : new ArrayList<>(vaccinations);
        record.instructionVersions = instructionVersions == null
                ? new ArrayList<>() : new ArrayList<>(instructionVersions);
        return record;
    }

    public void addTreatment(String description, LocalDate appliedDate) {
        treatments.add(CareTreatment.create(description, appliedDate));
    }

    public void addVaccination(String name, LocalDate appliedDate) {
        vaccinations.add(CareVaccination.create(name, appliedDate));
    }

    /** US19: creates the first instruction version. */
    public void registerInstructions(String content, UUID authorId, Instant now) {
        if (currentInstructions().isPresent()) {
            throw DomainException.conflict("INVALID_STATE_TRANSITION",
                    "Las indicaciones ya existen; use la operación de actualización");
        }
        instructionVersions.add(CareInstructionVersion.create(content, authorId, now));
    }

    /** US20: appends a new version, the previous one is preserved with author and date. */
    public void updateInstructions(String content, UUID authorId, Instant now) {
        if (currentInstructions().isEmpty()) {
            throw DomainException.business("INVALID_STATE_TRANSITION",
                    "No existen indicaciones para modificar; regístrelas primero");
        }
        instructionVersions.add(CareInstructionVersion.create(content, authorId, now));
    }

    public java.util.Optional<CareInstructionVersion> currentInstructions() {
        return instructionVersions.stream()
                .max(java.util.Comparator.comparing(CareInstructionVersion::getCreatedAt));
    }

    public boolean isAuthoredBy(UUID veterinarianId) {
        return veterinarianId != null && veterinarianId.equals(this.veterinarianId);
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw DomainException.badRequest("VALIDATION_ERROR", field + ": es obligatorio");
        }
    }

    private static void requireNotFuture(LocalDate date, String field) {
        if (date != null && date.isAfter(LocalDate.now(ZoneOffset.UTC))) {
            throw DomainException.badRequest("INVALID_DATE", field + ": la fecha no puede ser futura");
        }
    }
}
