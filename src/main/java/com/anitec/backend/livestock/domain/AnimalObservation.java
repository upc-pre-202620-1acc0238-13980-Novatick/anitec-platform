package com.anitec.backend.livestock.domain;

import com.anitec.backend.shared.domain.DomainException;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Observation written by the owning farmer about an animal (child entity of
 * the Animal aggregate; requirements.md: only the ganadero authors remarks).
 */
@Getter
public class AnimalObservation {

    private UUID id;
    private UUID authorId;
    private String text;
    private Instant createdAt;

    protected AnimalObservation() {
    }

    private AnimalObservation(UUID id, UUID authorId, String text, Instant createdAt) {
        this.id = id;
        this.authorId = authorId;
        this.text = text;
        this.createdAt = createdAt;
    }

    public static AnimalObservation create(String text, UUID authorId, Instant now) {
        if (text == null || text.isBlank()) {
            throw DomainException.badRequest("VALIDATION_ERROR", "text: la observación debe tener contenido");
        }
        return new AnimalObservation(UUID.randomUUID(), authorId, text.trim(), now);
    }

    public static AnimalObservation restore(UUID id, UUID authorId, String text, Instant createdAt) {
        return new AnimalObservation(id, authorId, text, createdAt);
    }
}
