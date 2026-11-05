package com.anitec.backend.identity.infrastructure;

import com.anitec.backend.identity.domain.Account;
import com.anitec.backend.identity.domain.AccountRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Infrastructure adapter for {@link AccountRepository}. The Spring Data
 * interface lives in its own top-level type (Spring Data only discovers
 * top-level repository interfaces).
 */
@Component
public class AccountRepositoryImpl implements AccountRepository {

    private final AccountJpaRepository jpa;

    public AccountRepositoryImpl(AccountJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Account save(Account account) {
        return jpa.saveAndFlush(AccountJpaEntity.fromDomain(account)).toDomain();
    }

    @Override
    public Optional<Account> findById(UUID accountId) {
        return jpa.findById(accountId).map(AccountJpaEntity::toDomain);
    }

    @Override
    public Optional<Account> findByIdForUpdate(UUID accountId) {
        return jpa.findByIdForUpdate(accountId).map(AccountJpaEntity::toDomain);
    }

    @Override
    public Optional<Account> findByEmail(String email) {
        return jpa.findByEmail(email).map(AccountJpaEntity::toDomain);
    }

    @Override
    public Optional<Account> findByEmailForUpdate(String email) {
        return jpa.findByEmailForUpdate(email).map(AccountJpaEntity::toDomain);
    }

    @Override
    public boolean existsByEmail(String email) {
        return jpa.existsByEmail(email);
    }

    @Override
    public List<Account> findByIds(Collection<UUID> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return List.of();
        }
        return jpa.findAllById(accountIds).stream().map(AccountJpaEntity::toDomain).toList();
    }


}
