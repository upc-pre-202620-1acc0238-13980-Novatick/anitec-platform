package com.anitec.backend.shared.domain;

/**
 * Shared user roles of the platform. One account has exactly one role/profile
 * (requirements.md + US25). ADMIN is not a self-registerable profile.
 */
public enum Role {
    GANADERO,
    VETERINARIO,
    ADMIN
}
