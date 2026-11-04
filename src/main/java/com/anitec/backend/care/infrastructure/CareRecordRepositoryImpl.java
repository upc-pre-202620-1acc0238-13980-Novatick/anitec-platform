package com.anitec.backend.care.infrastructure;

import com.anitec.backend.care.domain.CareRecord;
import com.anitec.backend.care.domain.CareRecordRepository;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataIntegrityViolationException;
import org.hibernate.exception.ConstraintViolationException;
import com.anitec.backend.shared.domain.DomainException;

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
        try {
            // Flush here so this exact constraint can be translated before transaction commit.
            return jpa.saveAndFlush(CareRecordJpaEntity.fromDomain(record)).toDomain();
        } catch (DataIntegrityViolationException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && "uq_care_records_appointment".equals(violation.getConstraintName())) {
                    throw DomainException.conflict("ATTENTION_ALREADY_EXISTS",
                            "La cita ya tiene una atención clínica registrada");
                }
            }
            throw failure;
        }
    }

    @Override
    public Optional<CareRecord> findById(UUID careRecordId) {
        return jpa.findById(careRecordId).map(CareRecordJpaEntity::toDomain);
    }

    @Override
    public boolean existsByAppointmentId(UUID appointmentId) {
        return jpa.existsByAppointmentId(appointmentId);
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
