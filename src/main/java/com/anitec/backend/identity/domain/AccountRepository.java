package com.anitec.backend.identity.domain;

import com.anitec.backend.shared.domain.Role;

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

    Optional<Account> findByEmail(String email);

    boolean existsByEmail(String email);

    /** Batch lookup used to resolve display names in other contexts. */
    List<Account> findByIds(Collection<UUID> accountIds);

    /** Admin search (spec endpoint #52): query over e-mail/name, optional role filter. */
    List<Account> search(String query, Role role, int offset, int limit);

    long countSearch(String query, Role role);
}
