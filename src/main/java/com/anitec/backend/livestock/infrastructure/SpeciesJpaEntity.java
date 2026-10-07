package com.anitec.backend.livestock.infrastructure;

import com.anitec.backend.livestock.domain.Species;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Persistence model for {@code livestock_species} (Flyway V2). */
@Entity
@Table(name = "livestock_species")
@Getter
@Setter
@NoArgsConstructor
public class SpeciesJpaEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    private String description;

    @Column(name = "photo_url")
    private String photoUrl;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public static SpeciesJpaEntity fromDomain(Species value) {
        SpeciesJpaEntity entity = new SpeciesJpaEntity();
        entity.setId(value.getId());
        entity.setName(value.getName());
        entity.setDescription(value.getDescription());
        entity.setPhotoUrl(value.getPhotoUrl());
        entity.setCreatedAt(JpaTimes.toJdbc(value.getCreatedAt()));
        return entity;
    }

    public Species toDomain() {
        return Species.restore(id, name, description, photoUrl, JpaTimes.toInstant(createdAt));
    }
}
