package com.anitec.backend.linking.application;

import com.anitec.backend.identity.application.IdentityQueries;
import com.anitec.backend.linking.domain.InvitationRepository;
import com.anitec.backend.linking.domain.LinkingCapacity;
import com.anitec.backend.linking.domain.LinkingCapacityRepository;
import com.anitec.backend.linking.domain.LinkingInvitation;
import com.anitec.backend.linking.domain.VeterinaryLink;
import com.anitec.backend.linking.domain.VeterinaryLinkRepository;
import com.anitec.backend.shared.domain.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Veterinary Linking read model (report class LinkingQueryService). Exposes
 * the authorization check consumed by Veterinary Care (context map section 5.2)
 * and the pending invitations / active links views for both profiles.
 */
@Service
@Transactional(readOnly = true)
public class LinkingQueryService {

    private final InvitationRepository invitations;
    private final VeterinaryLinkRepository links;
    private final LinkingCapacityRepository capacities;
    private final IdentityQueries identityQueries;

    public LinkingQueryService(InvitationRepository invitations, VeterinaryLinkRepository links,
                               LinkingCapacityRepository capacities, IdentityQueries identityQueries) {
        this.invitations = invitations;
        this.links = links;
        this.capacities = capacities;
        this.identityQueries = identityQueries;
    }

    // ------------------------------------------------------------- records

    public record PendingInvitationView(UUID invitationId, String farmerName, LocalDate sentDate) {
    }

    public record LinkView(UUID linkId, UUID farmerId, String farmerName, UUID vetId, String vetName,
                           Instant linkedAt, String status) {
    }

    // ------------------------------------------------------------- queries

    /** US16: pending invitations addressed to the authenticated veterinarian. */
    public List<PendingInvitationView> pendingInvitations(UUID veterinarianId) {
        List<LinkingInvitation> pending = invitations.findPendingByVeterinarianId(veterinarianId).stream()
                .filter(invitation -> invitation.isPending()
                        && Instant.now().isBefore(invitation.getExpiresAt()))
                .toList();
        Map<UUID, String> names = identityQueries.displayNames(
                pending.stream().map(LinkingInvitation::getFarmerId).toList());
        return pending.stream()
                .map(invitation -> new PendingInvitationView(
                        invitation.getId(),
                        names.getOrDefault(invitation.getFarmerId(), "Ganadero"),
                        LocalDate.ofInstant(invitation.getSentAt(), ZoneOffset.UTC)))
                .toList();
    }

    /** US17: active links seen from the farmer side. */
    public List<LinkView> activeLinksAsFarmer(UUID farmerId) {
        return toViews(links.findActiveByFarmerId(farmerId));
    }

    /** US17: active links seen from the veterinarian side. */
    public List<LinkView> activeLinksAsVeterinarian(UUID veterinarianId) {
        return toViews(links.findActiveByVeterinarianId(veterinarianId));
    }

    public LinkingCapacity capacityOf(UUID veterinarianId) {
        return capacities.findByVeterinarianId(veterinarianId)
                .orElseThrow(() -> DomainException.notFound("Capacidad de vinculación no encontrada"));
    }

    /** Contract consumed by Veterinary Care: is this vet authorized for this owner? */
    @Transactional(readOnly = true)
    public boolean isVetAuthorizedFor(UUID veterinarianId, UUID ownerId) {
        return links.existsActive(ownerId, veterinarianId);
    }

    /** Farmers whose animals the given vet may read (used to scope inventories). */
    public Set<UUID> activeFarmerIds(UUID veterinarianId) {
        return links.findActiveByVeterinarianId(veterinarianId).stream()
                .map(VeterinaryLink::getFarmerId)
                .collect(Collectors.toSet());
    }

    public long activeLinksCountAsFarmer(UUID farmerId) {
        return links.findActiveByFarmerId(farmerId).size();
    }

    public long activeLinksCountAsVeterinarian(UUID veterinarianId) {
        return links.countActiveByVeterinarianId(veterinarianId);
    }

    public VeterinaryLink requireLink(UUID linkId) {
        return links.findById(linkId)
                .orElseThrow(() -> DomainException.notFound("Vinculación no encontrada"));
    }

    // ------------------------------------------------------------- helpers

    private List<LinkView> toViews(List<VeterinaryLink> found) {
        Map<UUID, String> names = identityQueries.displayNames(found.stream()
                .flatMap(link -> java.util.stream.Stream.of(link.getFarmerId(), link.getVetId()))
                .toList());
        return found.stream()
                .map(link -> new LinkView(link.getId(), link.getFarmerId(),
                        names.getOrDefault(link.getFarmerId(), "Ganadero"),
                        link.getVetId(), names.getOrDefault(link.getVetId(), "Veterinario"),
                        link.getLinkedAt(), link.getStatus().name()))
                .toList();
    }
}
