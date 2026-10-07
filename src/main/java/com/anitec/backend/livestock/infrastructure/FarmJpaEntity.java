package com.anitec.backend.livestock.infrastructure;

import com.anitec.backend.livestock.domain.Farm;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Persistence model for {@code livestock_farms} (Flyway V2). */
@Entity
@Table(name = "livestock_farms")
@Getter
@Setter
@NoArgsConstructor
public class FarmJpaEntity {

    @Id
    private UUID id;

    @Column(name = "farmer_id", nullable = false)
    private UUID ownerId;

    @Column(nullable = false, length = 150)
    private String name;

    private String location;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public static FarmJpaEntity fromDomain(Farm farm) {
        FarmJpaEntity entity = new FarmJpaEntity();
        entity.setId(farm.getId());
        entity.setOwnerId(farm.getOwnerId());
        entity.setName(farm.getName());
        entity.setLocation(farm.getLocation());
        entity.setCreatedAt(JpaTimes.toJdbc(farm.getCreatedAt()));
        return entity;
    }

    public Farm toDomain() {
        return Farm.restore(id, ownerId, name, location, JpaTimes.toInstant(createdAt));
    }
}
