package com.anitec.backend.identity.application;

import com.anitec.backend.identity.domain.Account;
import java.time.Instant;
import java.util.UUID;

/** Stages a security message in the caller's transaction; does not call a provider. */
public interface SecurityEmailDelivery {
    void verification(Account account);
    void passwordRecovery(UUID account, UUID emission, String recipient, String code, Instant expires);
}
