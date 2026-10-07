package com.anitec.backend.livestock.domain;

import com.anitec.backend.shared.domain.DomainException;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Species reference entity (requirements.md: name + optional description and
 * photo). Managed through the Admin API; seeded with the five classic species.
 */
@Getter
public class Species {

    private UUID id;
    private String name;
    private String description;
    private String photoUrl;
    private Instant createdAt;

    protected Species() {
    }

    private Species(UUID id, String name, String description, String photoUrl, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.photoUrl = photoUrl;
        this.createdAt = createdAt;
    }

    public static Species create(String name, String description, String photoUrl, Instant now) {
        if (name == null || name.isBlank()) {
            throw DomainException.badRequest("VALIDATION_ERROR", "name: la especie debe tener nombre");
        }
        return new Species(UUID.randomUUID(), name.trim(), description, photoUrl, now);
    }

    public static Species restore(UUID id, String name, String description, String photoUrl, Instant createdAt) {
        return new Species(id, name, description, photoUrl, createdAt);
    }

    public void update(String name, String description, String photoUrl) {
        if (name == null || name.isBlank()) {
            throw DomainException.badRequest("VALIDATION_ERROR", "name: la especie debe tener nombre");
        }
        this.name = name.trim();
        this.description = description;
        this.photoUrl = photoUrl;
    }
}
