package com.anitec.backend.shared.infrastructure.security;

import com.anitec.backend.shared.domain.DomainException;
import com.anitec.backend.shared.domain.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * JWT access token provider (24h, spec section 8.2). Claims: sub = accountId,
 * email, role. Refresh tokens are opaque and handled by the identity context.
 */
@Component
public class JwtService {

    private static final String ROLE_CLAIM = "role";

    private final java.time.Clock clock;
    private final SecretKey key;
    private final long expirationMs;
    private final long refreshExpirationMs;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration-ms:86400000}") long expirationMs,
            @Value("${jwt.refresh-expiration-ms:604800000}") long refreshExpirationMs, java.time.Clock clock) {
        this.clock = clock;
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("jwt.secret must be at least 256 bits (32 bytes)");
        }
        if (expirationMs <= 0 || refreshExpirationMs <= 0) throw new IllegalStateException("Token lifetimes must be positive");
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
        this.refreshExpirationMs = refreshExpirationMs;
    }

    public String createAccessToken(UUID accountId, String email, Role role) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(accountId.toString())
                .claim("email", email)
                .claim(ROLE_CLAIM, role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMs)))
                .signWith(key)
                .compact();
    }

    public String createAccessToken(UUID accountId, String email, Role role, UUID sessionId, Instant now, Instant sessionExpiry) {
        Instant expires = now.plusMillis(expirationMs);
        if (expires.isAfter(sessionExpiry)) expires = sessionExpiry;
        return Jwts.builder().id(UUID.randomUUID().toString()).subject(accountId.toString())
                .claim("email", email).claim(ROLE_CLAIM, role.name()).claim("sid", sessionId.toString())
                .issuedAt(Date.from(now)).expiration(Date.from(expires)).signWith(key).compact();
    }

    /** Parses and validates the token; throws 401 on any problem. */
    public Claims parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).clock(() -> Date.from(clock.instant())).build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (claims.getExpiration() == null || !clock.instant().isBefore(claims.getExpiration().toInstant()))
                throw new io.jsonwebtoken.ExpiredJwtException(null, claims, "Expired access token");
            return claims;
        } catch (io.jsonwebtoken.ExpiredJwtException ex) {
            throw DomainException.unauthorized("ACCESS_TOKEN_EXPIRED", "Token de acceso expirado");
        } catch (Exception ex) {
            throw DomainException.unauthorized("INVALID_CREDENTIALS", "Token de acceso inválido o expirado");
        }
    }

    public long accessTokenTtlSeconds() {
        return expirationMs / 1000;
    }

    public long refreshExpirationMs() {
        return refreshExpirationMs;
    }
}
