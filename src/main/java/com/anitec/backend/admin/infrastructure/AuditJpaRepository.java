package com.anitec.backend.admin.infrastructure;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data repository for {@code admin_audit_log}. */
public interface AuditJpaRepository extends JpaRepository<AuditEntryJpaEntity, UUID> {

    List<AuditEntryJpaEntity> findAllBy(Pageable pageable);
}
