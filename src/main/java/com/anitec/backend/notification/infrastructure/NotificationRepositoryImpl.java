package com.anitec.backend.notification.infrastructure;

import com.anitec.backend.notification.domain.Notification;
import com.anitec.backend.notification.domain.NotificationRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Infrastructure adapter for {@link NotificationRepository}. */
@Component
public class NotificationRepositoryImpl implements NotificationRepository {

    private final NotificationJpaRepository jpa;

    public NotificationRepositoryImpl(NotificationJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Notification save(Notification notification) {
        return jpa.save(NotificationJpaEntity.fromDomain(notification)).toDomain();
    }

    @Override
    public Optional<Notification> findById(UUID notificationId) {
        return jpa.findById(notificationId).map(NotificationJpaEntity::toDomain);
    }

    @Override
    public List<Notification> findByUserIdOrderByReadAscCreatedAtDesc(UUID userId, int offset, int limit) {
        int page = limit <= 0 ? 0 : offset / limit;
        PageRequest pageable = PageRequest.of(page, limit <= 0 ? 20 : limit,
                Sort.by(Sort.Order.asc("read"), Sort.Order.desc("createdAt")));
        return jpa.findByUserId(userId, pageable).stream()
                .map(NotificationJpaEntity::toDomain).toList();
    }

    @Override
    public long countByUserId(UUID userId) {
        return jpa.countByUserId(userId);
    }
}
