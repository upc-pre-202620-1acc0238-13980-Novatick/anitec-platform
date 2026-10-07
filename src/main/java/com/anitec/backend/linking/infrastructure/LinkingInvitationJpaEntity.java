package com.anitec.backend.linking.infrastructure;

import com.anitec.backend.linking.domain.LinkingInvitation;
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

/** Persistence model for {@code linking_invitations} (Flyway V3). */
@Entity
@Table(name = "linking_invitations")
@Getter
@Setter
@NoArgsConstructor
public class LinkingInvitationJpaEntity {

    @Id
    private UUID id;

    @Column(name = "farmer_id", nullable = false)
    private UUID farmerId;

    @Column(name = "vet_email", nullable = false, length = 150)
    private String vetEmail;

    @Column(name = "vet_id")
    private UUID vetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LinkingInvitation.Status status;

    @Column(name = "email_delivery")
    private String emailDelivery;

    @Column(name = "sent_at", nullable = false)
    private OffsetDateTime sentAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "responded_at")
    private OffsetDateTime respondedAt;

    public static LinkingInvitationJpaEntity fromDomain(LinkingInvitation invitation) {
        LinkingInvitationJpaEntity entity = new LinkingInvitationJpaEntity();
        entity.setId(invitation.getId());
        entity.setFarmerId(invitation.getFarmerId());
        entity.setVetEmail(invitation.getVetEmail());
        entity.setVetId(invitation.getVetId());
        entity.setStatus(invitation.getStatus());
        entity.setEmailDelivery(invitation.getEmailDelivery());
        entity.setSentAt(toJdbc(invitation.getSentAt()));
        entity.setExpiresAt(toJdbc(invitation.getExpiresAt()));
        entity.setRespondedAt(toJdbc(invitation.getRespondedAt()));
        return entity;
    }

    public LinkingInvitation toDomain() {
        return LinkingInvitation.restore(id, farmerId, vetEmail, vetId, status, emailDelivery,
                toInstant(sentAt), toInstant(expiresAt), toInstant(respondedAt));
    }

    static OffsetDateTime toJdbc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
