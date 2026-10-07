package com.anitec.backend.identity.infrastructure;

import com.anitec.backend.identity.domain.RefreshToken;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Persistence model for {@code identity_refresh_tokens} (Flyway V1). */
@Entity
@Table(name = "identity_refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
public class RefreshTokenJpaEntity {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "token_digest", nullable = false, unique = true, length = 64)
    private String tokenDigest;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public static RefreshTokenJpaEntity fromDomain(RefreshToken token) {
        RefreshTokenJpaEntity entity = new RefreshTokenJpaEntity();
        entity.setId(token.getId());
        entity.setAccountId(token.getAccountId());
        entity.setTokenDigest(token.getTokenDigest());
        entity.setExpiresAt(AccountJpaEntity.toJdbc(token.getExpiresAt()));
        entity.setRevokedAt(AccountJpaEntity.toJdbc(token.getRevokedAt()));
        entity.setCreatedAt(AccountJpaEntity.toJdbc(token.getCreatedAt()));
        return entity;
    }

    public RefreshToken toDomain() {
        return RefreshToken.restore(id, accountId, tokenDigest,
                AccountJpaEntity.toInstant(expiresAt),
                AccountJpaEntity.toInstant(revokedAt),
                AccountJpaEntity.toInstant(createdAt));
    }
}
