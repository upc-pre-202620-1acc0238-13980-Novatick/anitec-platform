package com.anitec.backend.identity.interfaceapi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Clock;
/** Reuses the bounded guard algorithm, with an independent map and operation counters. */
@Component
public class PasswordRecoveryOriginLimiter {
    private final VerificationOriginLimiter guard;
    public PasswordRecoveryOriginLimiter(Clock clock,@Value("${identity.password-recovery.origin-limit:120}") int limit) {
        if (limit<1) throw new IllegalStateException("Recovery origin limit must be positive");
        guard=new VerificationOriginLimiter(clock,limit);
    }
    public void check(String origin,String operation) { guard.check(origin,operation); }
}
