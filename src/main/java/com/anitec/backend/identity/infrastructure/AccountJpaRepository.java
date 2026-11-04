package com.anitec.backend.identity.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@code identity_accounts}. */
public interface AccountJpaRepository extends JpaRepository<AccountJpaEntity, UUID> {

    Optional<AccountJpaEntity> findByEmail(String email);

    boolean existsByEmail(String email);


}
