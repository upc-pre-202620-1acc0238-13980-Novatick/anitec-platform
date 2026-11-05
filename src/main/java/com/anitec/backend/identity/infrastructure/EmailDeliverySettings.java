package com.anitec.backend.identity.infrastructure;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.security.MessageDigest;
import java.util.Base64;

/** Disabled unless explicitly enabled. No profile can bypass enabled-mode validation. */
@Component
public class EmailDeliverySettings {
    public final boolean enabled;
    public final String apiKey, from;
    public final int connectMillis, responseMillis, leaseSeconds, maxAttempts, pollMillis, batchSize;
    private final byte[] key;
    public EmailDeliverySettings(Environment env) {
        String mode=env.getProperty("email.delivery.mode","disabled");
        if (!mode.equals("disabled") && !mode.equals("resend")) throw new IllegalStateException("Invalid email delivery mode");
        enabled=mode.equals("resend");
        apiKey=env.getProperty("resend.api-key",""); from=env.getProperty("resend.from","");
        connectMillis=number(env,"email.delivery.connect-timeout-ms",3000,100,10000);
        responseMillis=number(env,"email.delivery.response-timeout-ms",10000,100,30000);
        leaseSeconds=number(env,"email.delivery.lease-seconds",60,5,300);
        maxAttempts=number(env,"email.delivery.max-attempts",5,1,5);
        pollMillis=number(env,"email.delivery.poll-ms",1000,100,60000);
        batchSize=number(env,"email.delivery.batch-size",10,1,100);
        if (leaseSeconds*1000 <= connectMillis+responseMillis+5000)
            throw new IllegalStateException("Email claim lease must exceed HTTP timeouts with a safety margin");
        if (!enabled) { key=null; return; }
        if (apiKey.isBlank() || apiKey.contains("\r") || apiKey.contains("\n") ||
                !from.matches("(?i)(?:[a-z0-9._%+-]+@notifications\\.vitalwatch\\.space|[^<>\\r\\n]*<[a-z0-9._%+-]+@notifications\\.vitalwatch\\.space>)"))
            throw new IllegalStateException("Resend requires credentials and an authorized sender");
        key=decode(env.getProperty("email.delivery.encryption-key",env.getProperty("EMAIL_OUTBOX_ENCRYPTION_KEY","")));
        for (String name:new String[]{"identity.sessions.recovery-key","SESSION_RECOVERY_KEY","identity.password-recovery.hmac-key","PASSWORD_RECOVERY_HMAC_KEY"}) {
            String other=env.getProperty(name,"");
            if (!other.isBlank() && MessageDigest.isEqual(key,decode(other)))
                throw new IllegalStateException("Email outbox encryption key must be independent");
        }
    }
    public byte[] encryptionKey() { if(key==null) throw new IllegalStateException("Email delivery disabled"); return key.clone(); }
    private static byte[] decode(String value) {
        try { byte[] result=Base64.getDecoder().decode(value); if(result.length==32) return result; }
        catch(IllegalArgumentException ignored) { }
        throw new IllegalStateException("Email delivery requires independent 32-byte Base64 keys");
    }
    private static int number(Environment e,String name,int fallback,int min,int max) {
        int value=e.getProperty(name,Integer.class,fallback);
        if(value<min || value>max) throw new IllegalStateException("Invalid email delivery timing or capacity");
        return value;
    }
}
