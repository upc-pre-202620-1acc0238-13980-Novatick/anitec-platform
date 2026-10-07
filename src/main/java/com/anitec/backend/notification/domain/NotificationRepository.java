package com.anitec.backend.notification.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Repository port for notifications. */
public interface NotificationRepository {

    Notification save(Notification notification);

    Optional<Notification> findById(UUID notificationId);

    List<Notification> findByUserIdOrderByReadAscCreatedAtDesc(UUID userId, int offset, int limit);

    long countByUserId(UUID userId);
}
