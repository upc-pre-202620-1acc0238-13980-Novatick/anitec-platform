package com.anitec.backend.care.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data repository for {@code care_records}. */
public interface CareRecordJpaRepository extends JpaRepository<CareRecordJpaEntity, UUID> {

    List<CareRecordJpaEntity> findByAnimalIdOrderByAttentionDateDesc(UUID animalId);

    long countByAnimalId(UUID animalId);

    List<CareRecordJpaEntity> findByVeterinarianIdOrderByAttentionDateDesc(UUID veterinarianId);
}
