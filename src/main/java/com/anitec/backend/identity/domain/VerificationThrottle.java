package com.anitec.backend.identity.domain;

import com.anitec.backend.shared.domain.DomainException;
import org.springframework.http.HttpStatus;
import java.time.Instant;

/** A server-computed availability, only exposed in an authorized verification context. */
public class VerificationThrottle extends DomainException {
    private final Instant retryAt;
    public VerificationThrottle(String code, String message, Instant retryAt) {
        super(HttpStatus.TOO_MANY_REQUESTS, code, message, null);
        this.retryAt = retryAt;
    }
    public Instant retryAt() { return retryAt; }
}
