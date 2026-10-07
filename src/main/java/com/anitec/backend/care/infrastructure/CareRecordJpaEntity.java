package com.anitec.backend.care.infrastructure;

import com.anitec.backend.care.domain.CareRecord;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Persistence model for {@code care_records} plus its three collection tables
 * {@code care_treatments}, {@code care_vaccinations} and
 * {@code care_instruction_versions} (Flyway V4).
 */
@Entity
@Table(name = "care_records")
@Getter
@Setter
@NoArgsConstructor
public class CareRecordJpaEntity {

    @Id
    private UUID id;

    @Column(name = "animal_id", nullable = false)
    private UUID animalId;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "veterinarian_id", nullable = false)
    private UUID veterinarianId;

    @Column(name = "appointment_id")
    private UUID appointmentId;

    @Column(nullable = false, length = 150)
    private String title;

    private String description;

    @Column(name = "attention_date", nullable = false)
    private LocalDate attentionDate;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "care_treatments", joinColumns = @JoinColumn(name = "care_record_id"))
    private Set<TreatmentRow> treatments = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "care_vaccinations", joinColumns = @JoinColumn(name = "care_record_id"))
    private Set<VaccinationRow> vaccinations = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "care_instruction_versions",
            joinColumns = @JoinColumn(name = "care_record_id"))
    private Set<InstructionRow> instructionVersions = new LinkedHashSet<>();

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    public static class TreatmentRow {

        @Column(name = "id")
        private UUID id;

        @Column(nullable = false)
        private String description;

        @Column(name = "applied_date", nullable = false)
        private LocalDate appliedDate;

        @Override
        public boolean equals(Object other) {
            return other instanceof TreatmentRow row && id != null && id.equals(row.id);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(id);
        }
    }

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    public static class VaccinationRow {

        @Column(name = "id")
        private UUID id;

        @Column(nullable = false, length = 150)
        private String name;

        @Column(name = "applied_date", nullable = false)
        private LocalDate appliedDate;

        @Override
        public boolean equals(Object other) {
            return other instanceof VaccinationRow row && id != null && id.equals(row.id);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(id);
        }
    }

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    public static class InstructionRow {

        @Column(name = "id")
        private UUID id;

        @Column(nullable = false)
        private String content;

        @Column(name = "author_id", nullable = false)
        private UUID authorId;

        @Column(name = "created_at", nullable = false)
        private OffsetDateTime createdAt;

        @Override
        public boolean equals(Object other) {
            return other instanceof InstructionRow row && id != null && id.equals(row.id);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(id);
        }
    }

    public static CareRecordJpaEntity fromDomain(CareRecord record) {
        CareRecordJpaEntity entity = new CareRecordJpaEntity();
        entity.setId(record.getId());
        entity.setAnimalId(record.getAnimalId());
        entity.setOwnerId(record.getOwnerId());
        entity.setVeterinarianId(record.getVeterinarianId());
        entity.setAppointmentId(record.getAppointmentId());
        entity.setTitle(record.getTitle());
        entity.setDescription(record.getDescription());
        entity.setAttentionDate(record.getAttentionDate());
        entity.setCreatedAt(AppointmentJpaEntity.toJdbc(record.getRegisteredAt()));

        Set<TreatmentRow> treatmentRows = new LinkedHashSet<>();
        for (CareRecord.CareTreatment treatment : record.getTreatments()) {
            TreatmentRow row = new TreatmentRow();
            row.setId(treatment.getId());
            row.setDescription(treatment.getDescription());
            row.setAppliedDate(treatment.getAppliedDate());
            treatmentRows.add(row);
        }
        entity.setTreatments(treatmentRows);

        Set<VaccinationRow> vaccinationRows = new LinkedHashSet<>();
        for (CareRecord.CareVaccination vaccination : record.getVaccinations()) {
            VaccinationRow row = new VaccinationRow();
            row.setId(vaccination.getId());
            row.setName(vaccination.getName());
            row.setAppliedDate(vaccination.getAppliedDate());
            vaccinationRows.add(row);
        }
        entity.setVaccinations(vaccinationRows);

        Set<InstructionRow> instructionRows = new LinkedHashSet<>();
        for (CareRecord.CareInstructionVersion version : record.getInstructionVersions()) {
            InstructionRow row = new InstructionRow();
            row.setId(version.getId());
            row.setContent(version.getContent());
            row.setAuthorId(version.getAuthorId());
            row.setCreatedAt(AppointmentJpaEntity.toJdbc(version.getCreatedAt()));
            instructionRows.add(row);
        }
        entity.setInstructionVersions(instructionRows);
        return entity;
    }

    public CareRecord toDomain() {
        List<CareRecord.CareTreatment> treatmentList = treatments.stream()
                .map(row -> CareRecord.CareTreatment.restore(row.getId(), row.getDescription(),
                        row.getAppliedDate()))
                .sorted(java.util.Comparator.comparing(CareRecord.CareTreatment::getId))
                .toList();
        List<CareRecord.CareVaccination> vaccinationList = vaccinations.stream()
                .map(row -> CareRecord.CareVaccination.restore(row.getId(), row.getName(),
                        row.getAppliedDate()))
                .sorted(java.util.Comparator.comparing(CareRecord.CareVaccination::getId))
                .toList();
        List<CareRecord.CareInstructionVersion> instructionList = instructionVersions.stream()
                .map(row -> CareRecord.CareInstructionVersion.restore(row.getId(), row.getContent(),
                        row.getAuthorId(), AppointmentJpaEntity.toInstant(row.getCreatedAt())))
                .sorted(java.util.Comparator.comparing(
                        CareRecord.CareInstructionVersion::getCreatedAt))
                .toList();
        return CareRecord.restore(id, animalId, ownerId, veterinarianId, appointmentId, title,
                description, attentionDate, AppointmentJpaEntity.toInstant(createdAt),
                new ArrayList<>(treatmentList), new ArrayList<>(vaccinationList),
                new ArrayList<>(instructionList));
    }
}
