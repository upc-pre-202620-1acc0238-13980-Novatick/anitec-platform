package com.anitec.backend.identity.domain;

import java.util.Optional;
import java.util.UUID;

/** Repository port for refresh tokens (rotation + server side logout). */
public interface RefreshTokenRepository {

    RefreshToken save(RefreshToken token);

    Optional<RefreshToken> findByDigest(String digest);
}
