package com.anitec.backend.shared.infrastructure.security;

import com.anitec.backend.shared.domain.DomainException;
import com.anitec.backend.shared.domain.Role;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/**
 * Small helper to read the authenticated account from the SecurityContext.
 * The JwtAuthFilter stores the accountId as the principal name.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            throw DomainException.unauthorized("INVALID_CREDENTIALS", "Se requiere una sesión válida");
        }
        return UUID.fromString(authentication.getName());
    }

    /** Role carried in the JWT authorities (ROLE_*). */
    public static Role role() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw DomainException.unauthorized("INVALID_CREDENTIALS", "Se requiere una sesión válida");
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String value = authority.getAuthority();
            if (value.startsWith("ROLE_")) {
                return Role.valueOf(value.substring(5));
            }
        }
        throw DomainException.unauthorized("INVALID_CREDENTIALS", "La sesión no tiene un rol válido");
    }
}
