package com.anitec.backend.identity.application;

import com.anitec.backend.identity.domain.Account;
import com.anitec.backend.identity.domain.AccountRepository;
import com.anitec.backend.shared.domain.DomainException;
import com.anitec.backend.shared.domain.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Identity read model (report class IdentityQueries). Public query API for
 * other contexts (vet lookup by e-mail for invitations) and for the Admin
 * context (paged user listing).
 */
@Service
@Transactional(readOnly = true)
public class IdentityQueries {

    private final AccountRepository accounts;

    public IdentityQueries(AccountRepository accounts) {
        this.accounts = accounts;
    }

    public Account getIdentity(UUID accountId) {
        return accounts.findById(accountId)
                .orElseThrow(() -> DomainException.notFound("Cuenta no encontrada"));
    }

    /** Used by Veterinary Linking to resolve/validate the invited veterinarian. */
    public Optional<Account> findByEmail(String email) {
        return accounts.findByEmail(Account.normalizeEmail(email));
    }

    /** Batch display names (full name) used by other contexts' read models. */
    public Map<UUID, String> displayNames(Collection<UUID> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return Map.of();
        }
        return accounts.findByIds(accountIds).stream()
                .collect(java.util.stream.Collectors.toMap(Account::getId, Account::fullName, (a, b) -> a));
    }

    public record AccountSummary(UUID id, String email, String name, String lastName,
                                 Role role, Account.Status status, boolean emailVerified) {
        public static AccountSummary from(Account account) {
            return new AccountSummary(account.getId(), account.getEmail(), account.getName(),
                    account.getLastName(), account.getRole(), account.getStatus(), account.isVerified());
        }
    }

    public record AccountPage(List<AccountSummary> content, int page, int size, long total) {
    }

    /** Admin user search (spec endpoint #52). */
    public AccountPage searchAccounts(String query, Role role, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        List<Account> found = accounts.search(query, role, safePage * safeSize, safeSize);
        long total = accounts.countSearch(query, role);
        return new AccountPage(found.stream().map(AccountSummary::from).toList(), safePage, safeSize, total);
    }
}
