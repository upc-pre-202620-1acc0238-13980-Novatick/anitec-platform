package com.anitec.backend.shared.infrastructure.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import com.anitec.backend.shared.domain.Role;

/**
 * Extracts {@code Authorization: Bearer <jwt>}, validates the signature and
 * populates the SecurityContext with ROLE_* authorities (spec section 10.1).
 * Every protected request validates the persistent session and current account.
 * Invalid credentials produce 401; unavailable state validation fails closed with 503.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final com.anitec.backend.identity.application.SessionService sessions;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    public JwtAuthFilter(JwtService jwtService, com.anitec.backend.identity.application.SessionService sessions, com.fasterxml.jackson.databind.ObjectMapper json) {
        this.jwtService = jwtService;
        this.sessions = sessions;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")
                && SecurityContextHolder.getContext().getAuthentication() == null) {

            String token = header.substring(7).trim();
            try {
                Claims claims = jwtService.parse(token);
                if (claims.getSubject() == null || claims.get("role", String.class) == null)
                    throw new IllegalArgumentException("Missing identity claims");
                UUID accountId = UUID.fromString(claims.getSubject());
                Role role = Role.valueOf(claims.get("role", String.class));
                String sid = claims.get("sid", String.class);
                if (sid == null || !sessions.authorized(UUID.fromString(sid), accountId, role)) {
                    throw com.anitec.backend.shared.domain.DomainException.unauthorized("SESSION_INVALID", "Sesión inválida");
                }

                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        accountId.toString(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (com.anitec.backend.shared.domain.DomainException failure) {
                request.setAttribute("authenticationErrorCode", failure.errorCode());
            } catch (IllegalArgumentException | io.jsonwebtoken.JwtException failure) {
                request.setAttribute("authenticationErrorCode", "INVALID_CREDENTIALS");
            } catch (Exception unavailable) {
                response.setStatus(503);
                response.setContentType("application/json");
                json.writeValue(response.getOutputStream(), com.anitec.backend.shared.interfaceapi.ApiResponse.error(
                        "No se pudo comprobar la sesión", "AUTHENTICATION_UNAVAILABLE", null));
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
