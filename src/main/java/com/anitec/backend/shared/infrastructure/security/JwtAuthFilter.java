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
import com.anitec.backend.identity.domain.AccountRepository;
import com.anitec.backend.shared.domain.Role;

/**
 * Extracts {@code Authorization: Bearer <jwt>}, validates the signature and
 * populates the SecurityContext with ROLE_* authorities (spec section 10.1).
 * Invalid/missing tokens simply leave the request unauthenticated, so the
 * entry point answers with the standard 401 envelope.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final AccountRepository accounts;

    public JwtAuthFilter(JwtService jwtService, AccountRepository accounts) {
        this.jwtService = jwtService;
        this.accounts = accounts;
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
                UUID accountId = UUID.fromString(claims.getSubject());
                Role role = Role.valueOf(claims.get("role", String.class));
                var account = accounts.findById(accountId).orElseThrow();
                if (!account.canSignIn() || account.getRole() != role) {
                    throw new IllegalArgumentException("Account is not authorized for this token");
                }

                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        accountId.toString(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (Exception ignored) {
                // Leave unauthenticated: the security entry point replies 401 with the envelope.
            }
        }
        filterChain.doFilter(request, response);
    }
}
