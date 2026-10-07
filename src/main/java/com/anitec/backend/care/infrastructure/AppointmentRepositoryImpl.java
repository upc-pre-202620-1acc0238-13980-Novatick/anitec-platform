package com.anitec.backend.care.infrastructure;

import com.anitec.backend.care.domain.AppointmentRepository;
import com.anitec.backend.care.domain.VeterinaryAppointment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Infrastructure adapter for {@link AppointmentRepository}. */
@Component
public class AppointmentRepositoryImpl implements AppointmentRepository {

    private final AppointmentJpaRepository jpa;

    public AppointmentRepositoryImpl(AppointmentJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public VeterinaryAppointment save(VeterinaryAppointment appointment) {
        return jpa.save(AppointmentJpaEntity.fromDomain(appointment)).toDomain();
    }

    @Override
    public Optional<VeterinaryAppointment> findById(UUID appointmentId) {
        return jpa.findById(appointmentId).map(AppointmentJpaEntity::toDomain);
    }

    @Override
    public List<VeterinaryAppointment> findByVeterinarianAndRange(UUID veterinarianId, Instant from, Instant to) {
        return jpa.findByVeterinarianIdAndScheduledAtBetweenOrderByScheduledAtAsc(veterinarianId,
                        OffsetDateTime.ofInstant(from, ZoneOffset.UTC),
                        OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                .stream().map(AppointmentJpaEntity::toDomain).toList();
    }
}
