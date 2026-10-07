package com.anitec.backend.linking.domain;

import com.anitec.backend.shared.domain.DomainEvent;
import com.anitec.backend.shared.domain.DomainException;
import lombok.Getter;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * LinkingInvitation aggregate (Veterinary Linking context): the farmer invites
 * a registered veterinarian by e-mail; only the addressee may respond and
 * accepting consumes a capacity slot of the vet's plan (US14-US16).
 */
@Getter
public class LinkingInvitation {

    public enum Status {PENDIENTE, ACEPTADA, RECHAZADA, VENCIDA}

    /** Provisional pending validity (open question flagged in spec section 16). */
    public static final Duration PENDING_VALIDITY = Duration.ofDays(7);

    public record InvitationSent(UUID invitationId, UUID farmerId, UUID vetId, String vetEmail)
            implements DomainEvent {
    }

    public record InvitationAnswered(UUID invitationId, UUID farmerId, UUID vetId, boolean accepted)
            implements DomainEvent {
    }

    private UUID id;
    private UUID farmerId;
    private String vetEmail;
    private UUID vetId;
    private Status status;
    private String emailDelivery;
    private Instant sentAt;
    private Instant expiresAt;
    private Instant respondedAt;

    protected LinkingInvitation() {
    }

    private LinkingInvitation(UUID id, UUID farmerId, String vetEmail, UUID vetId, Status status,
                              String emailDelivery, Instant sentAt, Instant expiresAt, Instant respondedAt) {
        this.id = id;
        this.farmerId = farmerId;
        this.vetEmail = vetEmail;
        this.vetId = vetId;
        this.status = status;
        this.emailDelivery = emailDelivery;
        this.sentAt = sentAt;
        this.expiresAt = expiresAt;
        this.respondedAt = respondedAt;
    }

    public static LinkingInvitation create(UUID farmerId, UUID vetId, String vetEmail, Instant now) {
        return new LinkingInvitation(UUID.randomUUID(), farmerId, vetEmail, vetId,
                Status.PENDIENTE, null, now, now.plus(PENDING_VALIDITY), null);
    }

    public static LinkingInvitation restore(UUID id, UUID farmerId, String vetEmail, UUID vetId,
                                            Status status, String emailDelivery, Instant sentAt,
                                            Instant expiresAt, Instant respondedAt) {
        return new LinkingInvitation(id, farmerId, vetEmail, vetId, status, emailDelivery,
                sentAt, expiresAt, respondedAt);
    }

    public void requirePending(Instant now) {
        if (status == Status.VENCIDA) {
            throw DomainException.business("INVITATION_EXPIRED",
                    "La invitación expiró; el ganadero debe enviar una nueva");
        }
        if (status != Status.PENDIENTE) {
            throw DomainException.conflict("INVALID_STATE_TRANSITION", "La invitación ya fue respondida");
        }
        if (now.isAfter(expiresAt)) {
            throw DomainException.business("INVITATION_EXPIRED",
                    "La invitación expiró; el ganadero debe enviar una nueva");
        }
    }

    /**
     * Lazily moves an overdue pending invitation to VENCIDA so it stops
     * occupying the uq_invitation_pending slot and a new invitation can be
     * sent for the same farmer/veterinarian pair. Idempotent.
     *
     * @return true when this call performed the transition
     */
    public boolean expireIfDue(Instant now) {
        if (status == Status.PENDIENTE && now.isAfter(expiresAt)) {
            status = Status.VENCIDA;
            return true;
        }
        return false;
    }

    public void accept(Instant now) {
        requirePending(now);
        this.status = Status.ACEPTADA;
        this.respondedAt = now;
    }

    public void reject(Instant now) {
        requirePending(now);
        this.status = Status.RECHAZADA;
        this.respondedAt = now;
    }

    public boolean isAddressedTo(UUID veterinarianId) {
        return vetId != null && vetId.equals(veterinarianId);
    }

    public boolean isPending() {
        return status == Status.PENDIENTE;
    }

    public void setEmailDelivery(String emailDelivery) {
        this.emailDelivery = emailDelivery;
    }
}
