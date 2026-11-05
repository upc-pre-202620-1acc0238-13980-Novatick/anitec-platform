package com.anitec.backend.identity.infrastructure;

import com.anitec.backend.identity.application.PasswordRecoveryVerifier;
import com.anitec.backend.identity.domain.EmailSender;
import com.anitec.backend.identity.domain.PasswordRecovery;
import com.anitec.backend.shared.domain.DomainException;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Explicitly disabled by default; never generates keys, including in tests. */
@Component
public class PasswordRecoverySecurity implements PasswordRecoveryVerifier {
    private final boolean enabled;
    private final byte[] key;
    public PasswordRecoverySecurity(
            @Value("${identity.password-recovery.enabled:false}") boolean enabled,
            @Value("${identity.password-recovery.hmac-key:${PASSWORD_RECOVERY_HMAC_KEY:}}") String configured,
            @Value("${identity.sessions.recovery-key:${SESSION_RECOVERY_KEY:}}") String sessionKey,
            EmailSender sender, Environment environment) {
        this.enabled = enabled;
        if (!enabled) { key = null; return; }
        try { key = Base64.getDecoder().decode(configured); }
        catch (IllegalArgumentException invalid) { throw new IllegalStateException("Invalid password recovery HMAC key encoding"); }
        if (key.length != 32) throw new IllegalStateException("Password recovery requires a 32-byte HMAC key");
        if (!sessionKey.isBlank()) {
            byte[] other;
            try { other = Base64.getDecoder().decode(sessionKey); }
            catch (IllegalArgumentException invalid) { throw new IllegalStateException("Invalid session key configuration"); }
            if (MessageDigest.isEqual(key, other)) throw new IllegalStateException("Password recovery and session keys must be independent");
        }
        var profiles = Arrays.asList(environment.getActiveProfiles());
        boolean isolatedTest = profiles.contains("test") && !profiles.contains("prod");
        if (!sender.passwordRecoveryConfigured() || (!isolatedTest && AopUtils.getTargetClass(sender) != ResendEmailSender.class))
            throw new IllegalStateException("Password recovery requires a configured real email sender outside isolated tests");
    }
    @Override public void requireAvailable() {
        if (!enabled) throw DomainException.unavailable("RECOVERY_UNAVAILABLE", "La recuperación no está disponible");
    }
    @Override public byte[] sign(UUID account, UUID emission, String code) {
        requireAvailable();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            String input = account + "\0" + PasswordRecovery.PURPOSE + "\0" + emission + "\0" + code;
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception unavailable) { throw new IllegalStateException("Password recovery verifier unavailable"); }
    }
}
