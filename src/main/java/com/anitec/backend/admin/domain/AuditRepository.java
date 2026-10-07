package com.anitec.backend.admin.domain;

import java.util.List;

/** Repository port for the admin audit log. */
public interface AuditRepository {

    AuditEntry save(AuditEntry entry);

    List<AuditEntry> findRecent(int offset, int limit);

    long count();
}
