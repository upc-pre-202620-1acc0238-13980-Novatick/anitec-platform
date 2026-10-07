package com.anitec.backend.care.infrastructure;

import com.anitec.backend.care.domain.CareRecord;
import com.anitec.backend.care.domain.CareRecordRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Infrastructure adapter for {@link CareRecordRepository}. */
@Component
public class CareRecordRepositoryImpl implements CareRecordRepository {

    private final CareRecordJpaRepository jpa;

    public CareRecordRepositoryImpl(CareRecordJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public CareRecord save(CareRecord record) {
        return jpa.save(CareRecordJpaEntity.fromDomain(record)).toDomain();
    }

    @Override
    public Optional<CareRecord> findById(UUID careRecordId) {
        return jpa.findById(careRecordId).map(CareRecordJpaEntity::toDomain);
    }

    @Override
    public List<CareRecord> findByAnimalIdOrderByAttentionDateDesc(UUID animalId) {
        return jpa.findByAnimalIdOrderByAttentionDateDesc(animalId).stream()
                .map(CareRecordJpaEntity::toDomain).toList();
    }

    @Override
    public long countByAnimalId(UUID animalId) {
        return jpa.countByAnimalId(animalId);
    }

    @Override
    public List<CareRecord> findByVeterinarianIdOrderByAttentionDateDesc(UUID veterinarianId) {
        return jpa.findByVeterinarianIdOrderByAttentionDateDesc(veterinarianId).stream()
                .map(CareRecordJpaEntity::toDomain).toList();
    }
}
