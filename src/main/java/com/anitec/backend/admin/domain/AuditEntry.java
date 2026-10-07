package com.anitec.backend.admin.domain;

import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/** Audit entry written for every admin mutation (decision #16). */
@Getter
public class AuditEntry {

    private UUID id;
    private UUID actorId;
    private String action;
    private String entityType;
    private UUID entityId;
    private String details;
    private Instant createdAt;

    protected AuditEntry() {
    }

    private AuditEntry(UUID id, UUID actorId, String action, String entityType, UUID entityId,
                       String details, Instant createdAt) {
        this.id = id;
        this.actorId = actorId;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.details = details;
        this.createdAt = createdAt;
    }

    public static AuditEntry create(UUID actorId, String action, String entityType, UUID entityId,
                                    String details) {
        return new AuditEntry(UUID.randomUUID(), actorId, action, entityType, entityId, details,
                Instant.now());
    }

    public static AuditEntry restore(UUID id, UUID actorId, String action, String entityType,
                                     UUID entityId, String details, Instant createdAt) {
        return new AuditEntry(id, actorId, action, entityType, entityId, details, createdAt);
    }
}
