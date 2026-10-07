package com.anitec.backend.care.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Repository port for the CareRecord aggregate (report contract). */
public interface CareRecordRepository {

    CareRecord save(CareRecord record);

    Optional<CareRecord> findById(UUID careRecordId);

    List<CareRecord> findByAnimalIdOrderByAttentionDateDesc(UUID animalId);

    long countByAnimalId(UUID animalId);

    List<CareRecord> findByVeterinarianIdOrderByAttentionDateDesc(UUID veterinarianId);
}
