package com.anitec.backend.care.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Repository ports of the Veterinary Care context. */
public interface AppointmentRepository {

    VeterinaryAppointment save(VeterinaryAppointment appointment);

    Optional<VeterinaryAppointment> findById(UUID appointmentId);

    /** Serialize clinical registration and state changes within the caller's transaction. */
    Optional<VeterinaryAppointment> findByIdForUpdate(UUID appointmentId);

    List<VeterinaryAppointment> findByVeterinarianAndRange(UUID veterinarianId, java.time.Instant from,
                                                           java.time.Instant to);

    List<VeterinaryAppointment> findUpcomingByOwner(UUID ownerId, java.time.Instant now);
}
