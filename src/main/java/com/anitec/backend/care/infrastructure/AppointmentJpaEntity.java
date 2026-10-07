package com.anitec.backend.care.infrastructure;

import com.anitec.backend.care.domain.VeterinaryAppointment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Persistence model for {@code care_appointments} (Flyway V4). */
@Entity
@Table(name = "care_appointments")
@Getter
@Setter
@NoArgsConstructor
public class AppointmentJpaEntity {

    @Id
    private UUID id;

    @Column(name = "animal_id", nullable = false)
    private UUID animalId;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "veterinarian_id", nullable = false)
    private UUID veterinarianId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VeterinaryAppointment.Type type;

    @Column(name = "scheduled_at", nullable = false)
    private OffsetDateTime scheduledAt;

    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VeterinaryAppointment.Status status;

    @Column(name = "origin_attention_id")
    private UUID originAttentionId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public static AppointmentJpaEntity fromDomain(VeterinaryAppointment appointment) {
        AppointmentJpaEntity entity = new AppointmentJpaEntity();
        entity.setId(appointment.getId());
        entity.setAnimalId(appointment.getAnimalId());
        entity.setOwnerId(appointment.getOwnerId());
        entity.setVeterinarianId(appointment.getVeterinarianId());
        entity.setType(appointment.getType());
        entity.setScheduledAt(toJdbc(appointment.getScheduledAt()));
        entity.setReason(appointment.getReason());
        entity.setStatus(appointment.getStatus());
        entity.setOriginAttentionId(appointment.getOriginAttentionId());
        entity.setCreatedAt(toJdbc(appointment.getCreatedAt()));
        return entity;
    }

    public VeterinaryAppointment toDomain() {
        return VeterinaryAppointment.restore(id, animalId, ownerId, veterinarianId, type,
                toInstant(scheduledAt), reason, status, originAttentionId, toInstant(createdAt));
    }

    static OffsetDateTime toJdbc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
