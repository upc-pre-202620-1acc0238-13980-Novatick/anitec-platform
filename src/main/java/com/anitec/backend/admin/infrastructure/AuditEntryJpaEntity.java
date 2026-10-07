package com.anitec.backend.admin.infrastructure;

import com.anitec.backend.admin.domain.AuditEntry;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Persistence model for {@code admin_audit_log} (Flyway V7). */
@Entity
@Table(name = "admin_audit_log")
@Getter
@Setter
@NoArgsConstructor
public class AuditEntryJpaEntity {

    @Id
    private UUID id;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Column(nullable = false, length = 60)
    private String action;

    @Column(name = "entity_type", nullable = false, length = 60)
    private String entityType;

    @Column(name = "entity_id")
    private UUID entityId;

    private String details;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public static AuditEntryJpaEntity fromDomain(AuditEntry entry) {
        AuditEntryJpaEntity entity = new AuditEntryJpaEntity();
        entity.setId(entry.getId());
        entity.setActorId(entry.getActorId());
        entity.setAction(entry.getAction());
        entity.setEntityType(entry.getEntityType());
        entity.setEntityId(entry.getEntityId());
        entity.setDetails(entry.getDetails());
        entity.setCreatedAt(toJdbc(entry.getCreatedAt()));
        return entity;
    }

    public AuditEntry toDomain() {
        return AuditEntry.restore(id, actorId, action, entityType, entityId, details, toInstant(createdAt));
    }

    static OffsetDateTime toJdbc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
