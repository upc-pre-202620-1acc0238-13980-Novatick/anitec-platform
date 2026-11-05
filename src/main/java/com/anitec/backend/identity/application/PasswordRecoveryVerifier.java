package com.anitec.backend.identity.application;
import java.util.UUID;
public interface PasswordRecoveryVerifier {
    void requireAvailable();
    byte[] sign(UUID accountId, UUID emissionId, String code);
}
