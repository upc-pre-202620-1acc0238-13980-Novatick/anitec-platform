package com.anitec.backend.admin.infrastructure;

import com.anitec.backend.admin.domain.AuditEntry;
import com.anitec.backend.admin.domain.AuditRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** Infrastructure adapter for {@link AuditRepository}. */
@Component
public class AuditRepositoryImpl implements AuditRepository {

    private final AuditJpaRepository jpa;

    public AuditRepositoryImpl(AuditJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public AuditEntry save(AuditEntry entry) {
        return jpa.save(AuditEntryJpaEntity.fromDomain(entry)).toDomain();
    }

    @Override
    public List<AuditEntry> findRecent(int offset, int limit) {
        int page = limit <= 0 ? 0 : offset / limit;
        PageRequest pageable = PageRequest.of(page, limit <= 0 ? 20 : limit,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return jpa.findAllBy(pageable).stream().map(AuditEntryJpaEntity::toDomain).toList();
    }

    @Override
    public long count() {
        return jpa.count();
    }
}
