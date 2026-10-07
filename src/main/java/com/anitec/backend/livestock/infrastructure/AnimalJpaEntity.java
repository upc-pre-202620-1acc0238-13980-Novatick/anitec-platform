package com.anitec.backend.livestock.infrastructure;

import com.anitec.backend.livestock.domain.Animal;
import com.anitec.backend.livestock.domain.AnimalObservation;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Persistence model for {@code livestock_animals} + its observations
 * collection table {@code livestock_observations} (Flyway V2). Cross-context
 * references (species/farm) are plain UUIDs without JPA associations.
 */
@Entity
@Table(name = "livestock_animals")
@Getter
@Setter
@NoArgsConstructor
public class AnimalJpaEntity {

    @Id
    private UUID id;

    @Column(name = "farm_id", nullable = false)
    private UUID farmId;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "species_id", nullable = false)
    private UUID speciesId;

    @Column(nullable = false, length = 50)
    private String code;

    private String name;

    private String breed;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Animal.Sex sex;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Animal.Status status;

    @Column(name = "photo_url")
    private String photoUrl;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "livestock_observations", joinColumns = @JoinColumn(name = "animal_id"))
    private Set<ObservationRow> observations = new LinkedHashSet<>();

    /** Row mapping of the domain's AnimalObservation child entity. */
    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    public static class ObservationRow {

        @Column(name = "id")
        private UUID id;

        @Column(name = "author_id")
        private UUID authorId;

        @Column(nullable = false)
        private String text;

        @Column(name = "created_at")
        private OffsetDateTime createdAt;

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ObservationRow row) || id == null) {
                return false;
            }
            return id.equals(row.id);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(id);
        }
    }

    public static AnimalJpaEntity fromDomain(Animal animal) {
        AnimalJpaEntity entity = new AnimalJpaEntity();
        entity.setId(animal.getId());
        entity.setFarmId(animal.getFarmId());
        entity.setOwnerId(animal.getOwnerId());
        entity.setSpeciesId(animal.getSpeciesId());
        entity.setCode(animal.codeValue());
        entity.setName(animal.getName());
        entity.setBreed(animal.getBreed());
        entity.setSex(animal.getSex());
        entity.setBirthDate(animal.getBirthDate());
        entity.setStatus(animal.getStatus());
        entity.setPhotoUrl(animal.getPhotoUrl());
        entity.setCreatedAt(JpaTimes.toJdbc(animal.getCreatedAt()));
        entity.setUpdatedAt(JpaTimes.toJdbc(animal.getUpdatedAt()));
        Set<ObservationRow> rows = new LinkedHashSet<>();
        for (AnimalObservation observation : animal.getObservations()) {
            ObservationRow row = new ObservationRow();
            row.setId(observation.getId());
            row.setAuthorId(observation.getAuthorId());
            row.setText(observation.getText());
            row.setCreatedAt(JpaTimes.toJdbc(observation.getCreatedAt()));
            rows.add(row);
        }
        entity.setObservations(rows);
        return entity;
    }

    public Animal toDomain() {
        List<AnimalObservation> restored = new ArrayList<>();
        for (ObservationRow row : observations) {
            restored.add(AnimalObservation.restore(row.getId(), row.getAuthorId(), row.getText(),
                    JpaTimes.toInstant(row.getCreatedAt())));
        }
        restored.sort(java.util.Comparator.comparing(AnimalObservation::getCreatedAt));
        return Animal.restore(id, farmId, ownerId, speciesId, code, name, breed, sex, birthDate, status,
                photoUrl, JpaTimes.toInstant(createdAt), JpaTimes.toInstant(updatedAt), restored);
    }
}
