package com.anitec.backend.identity.infrastructure;

import com.anitec.backend.identity.domain.Account;
import com.anitec.backend.identity.domain.AccountRepository;
import com.anitec.backend.shared.domain.Role;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@code identity_accounts}. */
public interface AccountJpaRepository extends JpaRepository<AccountJpaEntity, UUID> {

    Optional<AccountJpaEntity> findByEmail(String email);

    boolean existsByEmail(String email);

    @Query("""
            select a from AccountJpaEntity a
            where (:q = '' or lower(a.email) like lower(concat('%', :q, '%'))
                   or lower(a.name) like lower(concat('%', :q, '%'))
                   or lower(a.lastName) like lower(concat('%', :q, '%')))
              and (:role is null or a.role = :role)
            order by a.createdAt desc
            """)
    List<AccountJpaEntity> search(@Param("q") String q, @Param("role") Role role, Pageable pageable);

    @Query("""
            select count(a) from AccountJpaEntity a
            where (:q = '' or lower(a.email) like lower(concat('%', :q, '%'))
                   or lower(a.name) like lower(concat('%', :q, '%'))
                   or lower(a.lastName) like lower(concat('%', :q, '%')))
              and (:role is null or a.role = :role)
            """)
    long countSearch(@Param("q") String q, @Param("role") Role role);
}
