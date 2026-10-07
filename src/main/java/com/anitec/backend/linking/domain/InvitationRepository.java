package com.anitec.backend.linking.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Repository ports of the Veterinary Linking context. */
public interface InvitationRepository {

    LinkingInvitation save(LinkingInvitation invitation);

    Optional<LinkingInvitation> findById(UUID invitationId);

    List<LinkingInvitation> findPendingByVeterinarianId(UUID veterinarianId);

    /** The pair's pending invitation, whatever its expiry state (for lazy expiry). */
    Optional<LinkingInvitation> findPendingByPair(UUID farmerId, UUID veterinarianId);
}
