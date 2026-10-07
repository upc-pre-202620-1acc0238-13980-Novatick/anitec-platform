package com.anitec.backend.identity.infrastructure;

import com.anitec.backend.identity.domain.RefreshToken;
import com.anitec.backend.identity.domain.RefreshTokenRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** Infrastructure adapter for {@link RefreshTokenRepository}. */
@Component
public class RefreshTokenRepositoryImpl implements RefreshTokenRepository {

    private final RefreshTokenJpaRepository jpa;

    public RefreshTokenRepositoryImpl(RefreshTokenJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public RefreshToken save(RefreshToken token) {
        return jpa.save(RefreshTokenJpaEntity.fromDomain(token)).toDomain();
    }

    @Override
    public Optional<RefreshToken> findByDigest(String digest) {
        return jpa.findByTokenDigest(digest).map(RefreshTokenJpaEntity::toDomain);
    }
}
