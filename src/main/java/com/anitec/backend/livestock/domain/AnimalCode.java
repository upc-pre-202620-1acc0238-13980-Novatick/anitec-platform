package com.anitec.backend.livestock.domain;

import com.anitec.backend.shared.domain.DomainException;
import lombok.Getter;
import java.util.Locale;

/**
 * Normalized inventory label, unique per owner across current and historical reservations.
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
        String trimmed = value.strip();
        if (!trimmed.matches("[A-Za-z0-9-]{1,30}") || !trimmed.matches(".*[A-Za-z0-9].*")) {
            throw DomainException.badRequest("VALIDATION_ERROR",
                    "code: use entre 1 y 30 letras ASCII, números o guiones, con al menos una letra o número");
        }
        return new AnimalCode(trimmed.toUpperCase(Locale.ROOT));
    }
}
