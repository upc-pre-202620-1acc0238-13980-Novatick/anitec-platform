package com.anitec.backend.care.infrastructure;

import com.anitec.backend.care.domain.VeterinaryAppointment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Spring Data repository for {@code care_appointments}. */
public interface AppointmentJpaRepository extends JpaRepository<AppointmentJpaEntity, UUID> {

    List<AppointmentJpaEntity> findByVeterinarianIdAndScheduledAtBetweenOrderByScheduledAtAsc(
            UUID veterinarianId, OffsetDateTime from, OffsetDateTime to);
}
