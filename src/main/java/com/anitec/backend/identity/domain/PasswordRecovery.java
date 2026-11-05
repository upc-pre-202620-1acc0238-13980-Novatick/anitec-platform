package com.anitec.backend.identity.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Independent password recovery aggregate; never contains the plaintext code. */
public record PasswordRecovery(UUID accountId, Instant windowStartedAt, int emissions,
        Instant lastIssuedAt, UUID emissionId, byte[] verifier, Instant expiresAt,
        int failedAttempts, Instant consumedAt, Instant invalidatedAt) {
    public static final String PURPOSE = "PASSWORD_RECOVERY";
    public static final Duration WINDOW = Duration.ofHours(24);
    public static final Duration COOLDOWN = Duration.ofSeconds(60);
    public static final Duration TTL = Duration.ofMinutes(10);
    public static final int MAX_EMISSIONS = 5;
    public static final int MAX_ATTEMPTS = 5;

    public PasswordRecovery { verifier = verifier == null ? null : verifier.clone(); }
    @Override public byte[] verifier() { return verifier == null ? null : verifier.clone(); }

    public boolean allowsEmission(Instant now) {
        if (now.isBefore(lastIssuedAt.plus(COOLDOWN))) return false;
        return !now.isBefore(windowStartedAt.plus(WINDOW)) || emissions < MAX_EMISSIONS;
    }
    public static PasswordRecovery issue(UUID account, PasswordRecovery previous, UUID emission,
                                         byte[] verifier, Instant now) {
        if (previous != null && !previous.allowsEmission(now)) throw new IllegalStateException("Recovery issuance not available");
        boolean newWindow = previous == null || !now.isBefore(previous.windowStartedAt.plus(WINDOW));
        return new PasswordRecovery(account, newWindow ? now : previous.windowStartedAt,
                newWindow ? 1 : previous.emissions + 1, now, emission, verifier, now.plus(TTL), 0, null, null);
    }
    public boolean usable(Instant now) {
        return verifier != null && consumedAt == null && invalidatedAt == null
                && failedAttempts < MAX_ATTEMPTS && now.isBefore(expiresAt);
    }
    public PasswordRecovery failed(Instant now) {
        int next = Math.min(MAX_ATTEMPTS, failedAttempts + 1);
        return new PasswordRecovery(accountId, windowStartedAt, emissions, lastIssuedAt, emissionId,
                next == MAX_ATTEMPTS ? null : verifier, expiresAt, next, null,
                next == MAX_ATTEMPTS ? now : null);
    }
    public PasswordRecovery invalidate(Instant now) {
        if (verifier == null) return this;
        return new PasswordRecovery(accountId, windowStartedAt, emissions, lastIssuedAt, emissionId,
                null, expiresAt, failedAttempts, null, now);
    }
    public PasswordRecovery consume(Instant now) {
        if (!usable(now)) throw new IllegalStateException("Recovery code not usable");
        return new PasswordRecovery(accountId, windowStartedAt, emissions, lastIssuedAt, emissionId,
                null, expiresAt, failedAttempts, now, null);
    }
}
