package com.anitec.backend.shared.domain;

/**
 * Field level validation detail carried on error responses.
 */
public record FieldError(String field, String error) {
}
