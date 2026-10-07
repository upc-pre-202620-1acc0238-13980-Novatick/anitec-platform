package com.anitec.backend.notification.domain;

import com.anitec.backend.shared.domain.DomainException;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * In-app notification (decision #14: in-app table + push port). Push payloads
 * never carry clinical content (report rule): only a pointer that requires an
 * authorized session to read the actual details.
 */
@Getter
public class Notification {

    public enum Type {
        CARE_INSTRUCTIONS_REGISTERED,
        CARE_INSTRUCTIONS_UPDATED,
        VISIT_SCHEDULED,
        PREMIUM_ACTIVATED,
        PREMIUM_EXPIRED,
        INVITATION_RECEIVED,
        INVITATION_ANSWERED
    }

    private UUID id;
    private UUID userId;
    private Type type;
    private String title;
    private String message;
    private boolean read;
    private Instant createdAt;

    protected Notification() {
    }

    private Notification(UUID id, UUID userId, Type type, String title, String message,
                         boolean read, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.type = type;
        this.title = title;
        this.message = message;
        this.read = read;
        this.createdAt = createdAt;
    }

    public static Notification create(UUID userId, Type type, String title, String message, Instant now) {
        return new Notification(UUID.randomUUID(), userId, type, title, message, false, now);
    }

    public static Notification restore(UUID id, UUID userId, Type type, String title, String message,
                                       boolean read, Instant createdAt) {
        return new Notification(id, userId, type, title, message, read, createdAt);
    }

    /** Only the owner may mark their notification as read. */
    public void markRead(UUID requesterId) {
        if (!userId.equals(requesterId)) {
            throw DomainException.forbidden("La notificación no pertenece a su cuenta");
        }
        this.read = true;
    }
}
