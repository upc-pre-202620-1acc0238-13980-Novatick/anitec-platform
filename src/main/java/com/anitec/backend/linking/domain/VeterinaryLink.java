package com.anitec.backend.linking.domain;

import com.anitec.backend.shared.domain.DomainEvent;
import com.anitec.backend.shared.domain.DomainException;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * VeterinaryLink aggregate: the active authorization between a ganadero and a
 * veterinario. Revocation (US18) is farmer-only and idempotent; it releases
 * one linking capacity slot and never deletes the existing care history.
 */
@Getter
public class VeterinaryLink {

    public enum Status {ACTIVA, REVOCADA}

    public record LinkRevoked(UUID linkId, UUID farmerId, UUID vetId) implements DomainEvent {
    }

    private UUID id;
    private UUID invitationId;
    private UUID farmerId;
    private UUID vetId;
    private Status status;
    private Instant linkedAt;
    private Instant revokedAt;

    protected VeterinaryLink() {
    }

    private VeterinaryLink(UUID id, UUID invitationId, UUID farmerId, UUID vetId, Status status,
                           Instant linkedAt, Instant revokedAt) {
        this.id = id;
        this.invitationId = invitationId;
        this.farmerId = farmerId;
        this.vetId = vetId;
        this.status = status;
        this.linkedAt = linkedAt;
        this.revokedAt = revokedAt;
    }

    public static VeterinaryLink activate(UUID invitationId, UUID farmerId, UUID vetId, Instant now) {
        return new VeterinaryLink(UUID.randomUUID(), invitationId, farmerId, vetId,
                Status.ACTIVA, now, null);
    }

    public static VeterinaryLink restore(UUID id, UUID invitationId, UUID farmerId, UUID vetId,
                                         Status status, Instant linkedAt, Instant revokedAt) {
        return new VeterinaryLink(id, invitationId, farmerId, vetId, status, linkedAt, revokedAt);
    }

    /** @return true only when an active link was actually revoked (idempotent). */
    public boolean revoke(UUID ownerId, Instant now) {
        if (!belongsTo(ownerId)) {
            throw DomainException.forbidden("La vinculación no pertenece a su cuenta");
        }
        if (status != Status.ACTIVA) {
            return false;
        }
        this.status = Status.REVOCADA;
        this.revokedAt = now;
        return true;
    }

    public boolean isActive() {
        return status == Status.ACTIVA;
    }

    public boolean belongsTo(UUID ownerId) {
        return farmerId != null && farmerId.equals(ownerId);
    }
}
