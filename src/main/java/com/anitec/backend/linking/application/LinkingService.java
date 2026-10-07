package com.anitec.backend.linking.application;

import com.anitec.backend.identity.application.IdentityQueries;
import com.anitec.backend.identity.domain.Account;
import com.anitec.backend.identity.domain.EmailSender;
import com.anitec.backend.linking.domain.InvitationRepository;
import com.anitec.backend.linking.domain.LinkingCapacity;
import com.anitec.backend.linking.domain.LinkingCapacityRepository;
import com.anitec.backend.linking.domain.LinkingInvitation;
import com.anitec.backend.linking.domain.VeterinaryLink;
import com.anitec.backend.linking.domain.VeterinaryLinkRepository;
import com.anitec.backend.shared.application.DomainEventDispatcher;
import com.anitec.backend.shared.domain.DomainException;
import com.anitec.backend.shared.domain.Role;
import com.anitec.backend.subscriptions.domain.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Veterinary Linking command service (report class LinkingCommandService):
 * invitation lifecycle, acceptance with capacity checks, revocation and the
 * vet-side plan-limit events from Subscriptions (decision #12).
 */
@Service
public class LinkingService {

    private static final Logger log = LoggerFactory.getLogger(LinkingService.class);

    private final InvitationRepository invitations;
    private final VeterinaryLinkRepository links;
    private final LinkingCapacityRepository capacities;
    private final IdentityQueries identityQueries;
    private final EmailSender emailSender;
    private final DomainEventDispatcher events;

    public LinkingService(InvitationRepository invitations, VeterinaryLinkRepository links,
                          LinkingCapacityRepository capacities, IdentityQueries identityQueries,
                          EmailSender emailSender, DomainEventDispatcher events) {
        this.invitations = invitations;
        this.links = links;
        this.capacities = capacities;
        this.identityQueries = identityQueries;
        this.emailSender = emailSender;
        this.events = events;
    }

    public enum RespondAction {ACCEPT, REJECT}

    // ------------------------------------------------------------ commands

    /** US14: invite a registered, verified veterinarian by e-mail. */
    @Transactional
    public LinkingInvitation sendInvitation(UUID farmerId, String vetEmail) {
        String email = Account.normalizeEmail(vetEmail);
        Account vet = identityQueries.findByEmail(email)
                .filter(account -> account.getRole() == Role.VETERINARIO)
                .orElseThrow(() -> DomainException.business("RECIPIENT_NOT_REGISTERED",
                        "El correo no corresponde a un veterinario registrado"));
        if (vet.getId().equals(farmerId)) {
            throw DomainException.badRequest("VALIDATION_ERROR", "No puede invitarse a sí mismo");
        }
        if (links.existsActive(farmerId, vet.getId())) {
            throw DomainException.conflict("ALREADY_LINKED", "Ya existe una vinculación activa con este veterinario");
        }
        Instant now = Instant.now();
        if (hasLivePendingInvitation(farmerId, vet.getId(), now)) {
            throw DomainException.conflict("INVITATION_ALREADY_EXISTS",
                    "Ya existe una invitación pendiente con este veterinario");
        }

        LinkingInvitation invitation = LinkingInvitation.create(farmerId, vet.getId(), email, now);
        invitations.save(invitation);

        // E-mail failure keeps the invitation pending and reports UNCONFIRMED (US14 scenario 3).
        boolean sent;
        try {
            sent = emailSender.send(email, "ANITEC - Invitación de vinculación",
                    "Hola,\n\nEl ganadero le ha invitado a vincularse en ANITEC. "
                            + "Inicie sesión en la aplicación para aceptar o rechazar la invitación.");
        } catch (Exception ex) {
            log.warn("Invitation e-mail could not be sent to {}: {}", email, ex.getMessage());
            sent = false;
        }
        invitation.setEmailDelivery(sent ? "SENT" : "UNCONFIRMED");
        invitations.save(invitation);

        events.publish(new LinkingInvitation.InvitationSent(invitation.getId(), farmerId, vet.getId(), email));
        return invitation;
    }

    /** US15: the addressee vet accepts (capacity checked at accept) or rejects. */
    @Transactional
    public LinkingInvitation respond(UUID veterinarianId, UUID invitationId, RespondAction action) {
        LinkingInvitation invitation = invitations.findById(invitationId)
                .orElseThrow(() -> DomainException.notFound("Invitación no encontrada"));
        if (!invitation.isAddressedTo(veterinarianId)) {
            throw DomainException.forbidden("La invitación no está dirigida a su cuenta");
        }
        Instant now = Instant.now();

        if (action == RespondAction.ACCEPT) {
            invitation.requirePending(now);          // pending + not expired (INVITATION_EXPIRED)
            if (links.existsActive(invitation.getFarmerId(), veterinarianId)) {
                throw DomainException.conflict("ALREADY_LINKED", "Ya existe una vinculación activa con este ganadero");
            }
            LinkingCapacity capacity = requireCapacity(veterinarianId);
            capacity.occupySlot();                    // LINKING_LIMIT_REACHED when the plan limit is met

            VeterinaryLink link = VeterinaryLink.activate(
                    invitation.getId(), invitation.getFarmerId(), veterinarianId, now);
            invitation.accept(now);
            links.save(link);
            invitations.save(invitation);
            capacities.save(capacity);
            events.publish(new LinkingInvitation.InvitationAnswered(
                    invitation.getId(), invitation.getFarmerId(), veterinarianId, true));
        } else {
            invitation.reject(now);
            invitations.save(invitation);
            events.publish(new LinkingInvitation.InvitationAnswered(
                    invitation.getId(), invitation.getFarmerId(), veterinarianId, false));
        }
        return invitation;
    }

    /** US18: only the owning farmer may revoke; idempotent; frees one vet slot. */
    @Transactional
    public VeterinaryLink revokeLink(UUID farmerId, UUID linkId) {
        VeterinaryLink link = links.findById(linkId)
                .orElseThrow(() -> DomainException.notFound("Vinculación no encontrada"));
        if (!link.revoke(farmerId, Instant.now())) {
            return link; // already revoked: no double slot release
        }
        LinkingCapacity capacity = requireCapacity(link.getVetId());
        capacity.releaseSlot();
        links.save(link);
        capacities.save(capacity);
        events.publish(new VeterinaryLink.LinkRevoked(link.getId(), link.getFarmerId(), link.getVetId()));
        return link;
    }

    // ------------------------------------- subscriptions -> capacity limits

    @EventListener
    @Transactional
    public void onPremiumActivated(Subscription.PremiumActivated event) {
        if (event.profile() == Role.VETERINARIO) {
            applyLimit(event.accountId(), event.limit(), event.revision());
        }
    }

    @EventListener
    @Transactional
    public void onSubscriptionLimitChanged(Subscription.SubscriptionLimitChanged event) {
        if (event.profile() == Role.VETERINARIO) {
            applyLimit(event.accountId(), event.limit(), event.revision());
        }
    }

    @EventListener
    @Transactional
    public void onPremiumExpired(Subscription.PremiumExpired event) {
        if (event.profile() == Role.VETERINARIO) {
            applyLimit(event.accountId(), event.limit(), event.revision());
        }
    }

    private void applyLimit(UUID vetId, int limit, long revision) {
        LinkingCapacity capacity = capacities.findByVeterinarianId(vetId)
                .orElseGet(() -> LinkingCapacity.create(vetId, 0));
        if (capacity.updateLimit(limit, revision)) {
            capacities.save(capacity);
            events.publish(new LinkingCapacity.LinkingLimitUpdated(vetId, limit, revision));
        }
    }

    private LinkingCapacity requireCapacity(UUID vetId) {
        return capacities.findByVeterinarianId(vetId)
                .orElseThrow(() -> DomainException.internal(
                        "Capacidad de vinculación no inicializada para el veterinario " + vetId));
    }

    /**
     * True while a live (not yet expired) invitation is pending for the pair.
     * An expired one is persisted as VENCIDA first: that releases the
     * {@code uq_invitation_pending} partial index, which would otherwise keep
     * rejecting every new invitation (409) while the veterinarian can neither
     * see nor answer it - a permanent dead end after {@code PENDING_VALIDITY}.
     */
    private boolean hasLivePendingInvitation(UUID farmerId, UUID vetId, Instant now) {
        return invitations.findPendingByPair(farmerId, vetId)
                .map(invitation -> {
                    if (invitation.expireIfDue(now)) {
                        invitations.save(invitation);
                        return false;
                    }
                    return true;
                })
                .orElse(false);
    }
}
