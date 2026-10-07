package com.anitec.backend.notification.domain;

import java.util.UUID;

/**
 * Outbound push port (FCM in production, console adapter in this build).
 * Payloads must not contain clinical content (report rule US19/SP02).
 */
public interface PushNotificationPort {

    /**
     * @return true if the provider accepted the message; false otherwise.
     * Failures never roll back the business operation (US19 scenario 3).
     */
    boolean send(UUID userId, String title, String body);
}
