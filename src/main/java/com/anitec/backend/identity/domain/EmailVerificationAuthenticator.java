package com.anitec.backend.identity.domain;

import java.util.UUID;

/** Secret-backed proof; the aggregate never needs to recover a persisted code. */
public interface EmailVerificationAuthenticator {
    byte[] sign(UUID account, UUID emission, String code);
}
