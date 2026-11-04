package com.anitec.backend.care.infrastructure;

import com.anitec.backend.care.domain.VeterinaryAppointment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Spring Data repository for {@code care_appointments}. */
public interface AppointmentJpaRepository extends JpaRepository<AppointmentJpaEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AppointmentJpaEntity a where a.id = :id")
    java.util.Optional<AppointmentJpaEntity> findByIdForUpdate(@Param("id") UUID id);

    List<AppointmentJpaEntity> findByVeterinarianIdAndScheduledAtBetweenOrderByScheduledAtAsc(
            UUID veterinarianId, OffsetDateTime from, OffsetDateTime to);
}
