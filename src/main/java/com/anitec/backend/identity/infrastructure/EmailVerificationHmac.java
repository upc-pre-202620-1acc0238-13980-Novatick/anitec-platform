package com.anitec.backend.identity.infrastructure;

import com.anitec.backend.identity.domain.EmailVerificationAuthenticator;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

@Component
public final class EmailVerificationHmac implements EmailVerificationAuthenticator {
    public static final String PURPOSE_VERSION="email-verification:v1";
    private final byte[] key;
    public EmailVerificationHmac(Environment environment){key=validatedKey(environment);}
    public static byte[] validatedKey(Environment environment) {
        String configured=environment.getProperty("identity.email-verification.hmac-key",
                environment.getProperty("EMAIL_VERIFICATION_HMAC_KEY",""));
        byte[] key;
        try {key=Base64.getDecoder().decode(configured);}
        catch(IllegalArgumentException invalid){throw new IllegalStateException("Invalid email verification HMAC key encoding");}
        if(key.length!=32)throw new IllegalStateException("Email verification requires a stable 32-byte Base64 HMAC key");
        for(String name:new String[]{"identity.sessions.recovery-key","SESSION_RECOVERY_KEY",
                "identity.password-recovery.hmac-key","PASSWORD_RECOVERY_HMAC_KEY",
                "email.delivery.encryption-key","EMAIL_OUTBOX_ENCRYPTION_KEY","jwt.secret","JWT_SECRET"}) {
            String other=environment.getProperty(name,"");if(other.isBlank())continue;
            boolean same=MessageDigest.isEqual(key,other.getBytes(StandardCharsets.UTF_8));
            try {same|=MessageDigest.isEqual(key,Base64.getDecoder().decode(other));}
            catch(IllegalArgumentException notBase64){ /* JWT secrets can be literal strings. */ }
            if(same)throw new IllegalStateException("Email verification HMAC key must be independent of other security keys");
        }
        return key;
    }
    @Override public byte[] sign(UUID account,UUID emission,String code) {
        if(account==null || emission==null || code==null || !code.matches("[0-9]{6}"))
            throw new IllegalArgumentException("Invalid email verification proof input");
        try {
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
            return mac.doFinal((PURPOSE_VERSION+"\0"+account+"\0"+emission+"\0"+code).getBytes(StandardCharsets.UTF_8));
        } catch(Exception unavailable){throw new IllegalStateException("Email verification proof unavailable");}
    }
}
