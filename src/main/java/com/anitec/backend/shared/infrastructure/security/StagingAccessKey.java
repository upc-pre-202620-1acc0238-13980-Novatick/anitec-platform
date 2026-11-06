package com.anitec.backend.shared.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Staging-only key validation. Never include configured values in diagnostics. */
public final class StagingAccessKey {
    private StagingAccessKey() { }

    public static boolean active(Environment environment) {
        return environment.acceptsProfiles(Profiles.of("staging"));
    }

    public static byte[] validated(Environment environment) {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("Staging requires the prod profile as well as staging");
        }
        byte[] key;
        try {
            key = decode(environment.getProperty("staging.access.key",
                    environment.getProperty("STAGING_ACCESS_KEY", "")));
        } catch (IllegalArgumentException invalidConfiguration) {
            // Placeholder resolution errors can contain configured values: discard their cause.
            throw new IllegalStateException("Staging requires a canonical Base64 32-byte access key");
        }
        if (key == null) {
            throw new IllegalStateException("Staging requires a canonical Base64 32-byte access key");
        }
        boolean repeated = true;
        for (byte value : key) repeated &= value == key[0];
        if (repeated) throw new IllegalStateException("Staging requires an independently generated random access key");
        for (String name : new String[]{"jwt.secret", "JWT_SECRET",
                "identity.sessions.recovery-key", "SESSION_RECOVERY_KEY",
                "identity.email-verification.hmac-key", "EMAIL_VERIFICATION_HMAC_KEY",
                "identity.password-recovery.hmac-key", "PASSWORD_RECOVERY_HMAC_KEY",
                "email.delivery.encryption-key", "EMAIL_OUTBOX_ENCRYPTION_KEY"}) {
            String other = environment.getProperty(name, "");
            boolean same = MessageDigest.isEqual(key, other.getBytes(StandardCharsets.UTF_8));
            try { same |= MessageDigest.isEqual(key, Base64.getDecoder().decode(other)); }
            catch (IllegalArgumentException notBase64) { /* JWT can be a literal value. */ }
            if (same) throw new IllegalStateException("Staging access key must be independent of other security keys");
        }
        return key;
    }

    static byte[] decode(String value) {
        if (value == null || !value.matches("[A-Za-z0-9+/]{43}=")) return null;
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            return decoded.length == 32 && Base64.getEncoder().encodeToString(decoded).equals(value)
                    ? decoded : null;
        } catch (IllegalArgumentException invalid) { return null; }
    }
}
