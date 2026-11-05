package com.anitec.backend.identity.domain;


import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository port for the Account aggregate. Implemented in infrastructure;
 * the domain never depends on JPA/Spring Data (report contract:
 * AccountRepository).
 */
public interface AccountRepository {

    Account save(Account account);

    Optional<Account> findById(UUID accountId);

    Optional<Account> findByIdForUpdate(UUID accountId);

    Optional<Account> findByEmail(String email);

    /** Serialize verification and resend commands for the same account within a transaction. */
    Optional<Account> findByEmailForUpdate(String email);

    boolean existsByEmail(String email);

    /** Batch lookup used to resolve display names in other contexts. */
    List<Account> findByIds(Collection<UUID> accountIds);


}
