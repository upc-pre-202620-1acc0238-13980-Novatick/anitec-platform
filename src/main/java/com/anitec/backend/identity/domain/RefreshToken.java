package com.anitec.backend.identity.domain;

import lombok.Getter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Server side refresh token (spec section 8.2): an opaque 256 bit random
 * value stored only as a SHA-256 digest so logout/rotation can revoke it.
 * Access tokens remain stateless JWTs.
 */
@Getter
public class RefreshToken {

    private UUID id;
    private UUID accountId;
    private String tokenDigest;
    private Instant expiresAt;
    private Instant revokedAt;
    private Instant createdAt;

    private static final SecureRandom RANDOM = new SecureRandom();

    protected RefreshToken() {
    }

    private RefreshToken(UUID id, UUID accountId, String tokenDigest, Instant expiresAt,
                         Instant revokedAt, Instant createdAt) {
        this.id = id;
        this.accountId = accountId;
        this.tokenDigest = tokenDigest;
        this.expiresAt = expiresAt;
        this.revokedAt = revokedAt;
        this.createdAt = createdAt;
    }

    public static String newRawToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String digest(String rawToken) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    public static RefreshToken issue(UUID accountId, String rawToken, Instant now, long ttlMs) {
        return new RefreshToken(UUID.randomUUID(), accountId, digest(rawToken),
                now.plusMillis(ttlMs), null, now);
    }

    public static RefreshToken restore(UUID id, UUID accountId, String tokenDigest,
                                       Instant expiresAt, Instant revokedAt, Instant createdAt) {
        return new RefreshToken(id, accountId, tokenDigest, expiresAt, revokedAt, createdAt);
    }

    public boolean isValidAt(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }
}
