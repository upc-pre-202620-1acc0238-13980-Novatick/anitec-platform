package com.anitec.backend.notification.infrastructure;

import com.anitec.backend.notification.domain.PushNotificationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Console push adapter: this build logs the push attempt (decision #14). The
 * FCM adapter replaces this bean when Firebase credentials are configured.
 */
@Component
public class ConsolePushNotificationAdapter implements PushNotificationPort {

    private static final Logger log = LoggerFactory.getLogger(ConsolePushNotificationAdapter.class);

    @Override
    public boolean send(UUID userId, String title, String body) {
        log.info("[PUSH CONSOLE FALLBACK] user={} | {} | {}", userId, title, body);
        return true;
    }
}
