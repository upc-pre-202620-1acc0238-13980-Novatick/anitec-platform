package com.anitec.backend.livestock.domain;

import com.anitec.backend.shared.domain.DomainException;
import lombok.Getter;

/**
 * AnimalCode value object: a non-empty, immutable code assigned by the farmer
 * (e.g. BOV-024). Unique per farm across all statuses (spec section 9.4).
 */
@Getter
public class AnimalCode {

    private final String value;

    private AnimalCode(String value) {
        this.value = value;
    }

    public static AnimalCode of(String value) {
        if (value == null || value.isBlank()) {
            throw DomainException.badRequest("VALIDATION_ERROR", "code: es obligatorio");
        }
        String trimmed = value.trim();
        if (trimmed.length() > 50) {
            throw DomainException.badRequest("VALIDATION_ERROR", "code: máximo 50 caracteres");
        }
        return new AnimalCode(trimmed);
    }
}
