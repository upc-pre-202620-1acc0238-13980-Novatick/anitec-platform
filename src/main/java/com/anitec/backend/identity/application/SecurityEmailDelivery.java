package com.anitec.backend.identity.application;

import com.anitec.backend.identity.domain.Account;
import com.anitec.backend.identity.domain.EmailLanguage;
import java.time.Instant;
import java.util.UUID;

/** Stages a security message in the caller's transaction; does not call a provider. */
public interface SecurityEmailDelivery {
    void verification(Account account);
    void passwordRecovery(UUID account, UUID emission, String recipient, String code, Instant expires);

    /** Legacy adapters retain Spanish behavior; the production outbox overrides these overloads. */
    default void verification(Account account, EmailLanguage language) { verification(account); }
    default void passwordRecovery(UUID account, UUID emission, String recipient, String code, Instant expires,
                                  EmailLanguage language) { passwordRecovery(account, emission, recipient, code, expires); }
}
