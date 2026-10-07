package com.anitec.backend.livestock.domain;

import com.anitec.backend.shared.domain.DomainException;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Farm aggregate (spec v1 model, decision #2): a ganadero owns one or more
 * farms; animals belong to a farm and their code is unique within it.
 */
@Getter
public class Farm {

    private UUID id;
    private UUID ownerId;
    private String name;
    private String location;
    private Instant createdAt;

    protected Farm() {
    }

    private Farm(UUID id, UUID ownerId, String name, String location, Instant createdAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.name = name;
        this.location = location;
        this.createdAt = createdAt;
    }

    public static Farm create(UUID ownerId, String name, String location, Instant now) {
        if (name == null || name.isBlank()) {
            throw DomainException.badRequest("VALIDATION_ERROR", "name: la finca debe tener nombre");
        }
        return new Farm(UUID.randomUUID(), ownerId, name.trim(), location, now);
    }

    public static Farm restore(UUID id, UUID ownerId, String name, String location, Instant createdAt) {
        return new Farm(id, ownerId, name, location, createdAt);
    }

    public boolean belongsTo(UUID ownerId) {
        return this.ownerId != null && this.ownerId.equals(ownerId);
    }
}
