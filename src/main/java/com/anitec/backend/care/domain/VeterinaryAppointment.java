package com.anitec.backend.care.domain;

import com.anitec.backend.shared.domain.DomainEvent;
import com.anitec.backend.shared.domain.DomainException;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * VeterinaryAppointment aggregate (Veterinary Care context): scheduled visits
 * and follow-up controls. Scheduling a visit never equals a performed
 * attention (report rules); only the assigned vet manages its status (US07,
 * US08, US12).
 */
@Getter
public class VeterinaryAppointment {

    public enum Type {VISITA, CONTROL}

    public enum Status {PROGRAMADA, COMPLETADA, CANCELADA}

    public record VisitScheduled(UUID appointmentId, UUID animalId, UUID ownerId, UUID veterinarianId,
                                 Instant scheduledAt, Type type) implements DomainEvent {
    }

    private UUID id;
    private UUID animalId;
    private UUID ownerId;
    private UUID veterinarianId;
    private Type type;
    private Instant scheduledAt;
    private String reason;
    private Status status;
    private UUID originAttentionId;
    private Instant createdAt;

    protected VeterinaryAppointment() {
    }

    private VeterinaryAppointment(UUID id, UUID animalId, UUID ownerId, UUID veterinarianId, Type type,
                                  Instant scheduledAt, String reason, Status status,
                                  UUID originAttentionId, Instant createdAt) {
        this.id = id;
        this.animalId = animalId;
        this.ownerId = ownerId;
        this.veterinarianId = veterinarianId;
        this.type = type;
        this.scheduledAt = scheduledAt;
        this.reason = reason;
        this.status = status;
        this.originAttentionId = originAttentionId;
        this.createdAt = createdAt;
    }

    public static VeterinaryAppointment scheduleVisit(UUID animalId, UUID ownerId, UUID veterinarianId,
                                                      Instant scheduledAt, String reason, Instant now) {
        requireFuture(scheduledAt, now);
        return new VeterinaryAppointment(UUID.randomUUID(), animalId, ownerId, veterinarianId,
                Type.VISITA, scheduledAt, reason, Status.PROGRAMADA, null, now);
    }

    public static VeterinaryAppointment scheduleFollowUp(UUID originAttentionId, UUID animalId, UUID ownerId,
                                                         UUID veterinarianId, Instant scheduledAt,
                                                         String reason, Instant now) {
        requireFuture(scheduledAt, now);
        return new VeterinaryAppointment(UUID.randomUUID(), animalId, ownerId, veterinarianId,
                Type.CONTROL, scheduledAt, reason, Status.PROGRAMADA, originAttentionId, now);
    }

    public static VeterinaryAppointment restore(UUID id, UUID animalId, UUID ownerId, UUID veterinarianId,
                                               Type type, Instant scheduledAt, String reason, Status status,
                                               UUID originAttentionId, Instant createdAt) {
        return new VeterinaryAppointment(id, animalId, ownerId, veterinarianId, type, scheduledAt,
                reason, status, originAttentionId, createdAt);
    }

    /** @return true only when the status actually changed (idempotent). */
    public boolean changeStatus(Status newStatus, UUID requestedBy, Instant now) {
        if (!veterinarianId.equals(requestedBy)) {
            throw DomainException.forbidden("Solo el veterinario asignado puede modificar esta cita");
        }
        if (status == newStatus) {
            return false;
        }
        if (status != Status.PROGRAMADA) {
            throw DomainException.conflict("INVALID_STATE_TRANSITION",
                    "La cita ya fue " + status.name().toLowerCase());
        }
        this.status = newStatus;
        return true;
    }

    public boolean isAssignedTo(UUID veterinarianId) {
        return veterinarianId != null && veterinarianId.equals(this.veterinarianId);
    }

    private static void requireFuture(Instant scheduledAt, Instant now) {
        if (scheduledAt == null || !scheduledAt.isAfter(now)) {
            throw DomainException.badRequest("INVALID_DATE", "scheduledAt: la fecha debe ser futura");
        }
    }
}
