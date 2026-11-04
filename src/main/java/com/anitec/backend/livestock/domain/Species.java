package com.anitec.backend.livestock.domain;

import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Species reference entity (requirements.md: name + optional description and
 * photo). Seeded through versioned reference data.
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

    public static Species restore(UUID id, String name, String description, String photoUrl, Instant createdAt) {
        return new Species(id, name, description, photoUrl, createdAt);
    }

}
