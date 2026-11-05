package com.anitec.backend.identity.domain;
import java.util.Optional;
import java.util.UUID;
/** Writers must lock the owning account first, including initial issuance. */
public interface PasswordRecoveryRepository {
    Optional<PasswordRecovery> find(UUID accountId);
    void save(PasswordRecovery recovery);
}
