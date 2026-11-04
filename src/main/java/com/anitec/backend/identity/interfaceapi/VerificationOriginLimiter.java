package com.anitec.backend.identity.interfaceapi;

import com.anitec.backend.identity.domain.VerificationThrottle;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Small, bounded per-process guard for verification endpoints, not a general rate-limit framework. */
@Component
public class VerificationOriginLimiter {
    private record Window(Instant start, int requests) { }
    private final Map<String, Window> windows = new HashMap<>();
    private final Clock clock;
    private final int limit;
    public VerificationOriginLimiter() { this(Clock.systemUTC(), 120); }
    public VerificationOriginLimiter(Clock clock, int limit) { this.clock = clock; this.limit = limit; }

    public synchronized void check(String origin, String operation) {
        Instant now = clock.instant();
        windows.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().start().plusSeconds(60)));
        String key = operation + ":" + origin; // Do not trust arbitrary X-Forwarded-For headers.
        Window window = windows.get(key);
        if (window == null && windows.size() >= 2048) {
            throw throttled(now.plusSeconds(60));
        }
        if (window == null) window = new Window(now, 0);
        if (window.requests() >= limit) throw throttled(window.start().plusSeconds(60));
        windows.put(key, new Window(window.start(), window.requests() + 1));
    }
    private VerificationThrottle throttled(Instant retryAt) {
        return new VerificationThrottle("VERIFICATION_ORIGIN_LIMIT", "Demasiadas solicitudes desde este origen", retryAt);
    }
}
