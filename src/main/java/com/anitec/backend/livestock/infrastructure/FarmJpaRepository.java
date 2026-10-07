package com.anitec.backend.livestock.infrastructure;

import com.anitec.backend.livestock.domain.Farm;
import com.anitec.backend.livestock.domain.FarmRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@code livestock_farms}. */
public interface FarmJpaRepository extends JpaRepository<FarmJpaEntity, UUID> {

    List<FarmJpaEntity> findByOwnerIdOrderByCreatedAtAsc(UUID ownerId);
}
