package com.anitec.backend.care.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Repository ports of the Veterinary Care context. */
public interface AppointmentRepository {

    VeterinaryAppointment save(VeterinaryAppointment appointment);

    Optional<VeterinaryAppointment> findById(UUID appointmentId);

    List<VeterinaryAppointment> findByVeterinarianAndRange(UUID veterinarianId, java.time.Instant from,
                                                           java.time.Instant to);
}
