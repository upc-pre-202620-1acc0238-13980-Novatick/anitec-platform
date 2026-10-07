package com.anitec.backend.care.application;

import com.anitec.backend.care.domain.AppointmentRepository;
import com.anitec.backend.care.domain.CareRecord;
import com.anitec.backend.care.domain.CareRecordRepository;
import com.anitec.backend.care.domain.VeterinaryAppointment;
import com.anitec.backend.linking.application.LinkingQueryService;
import com.anitec.backend.livestock.application.LivestockQueries;
import com.anitec.backend.livestock.domain.Animal;
import com.anitec.backend.shared.application.DomainEventDispatcher;
import com.anitec.backend.shared.domain.DomainException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Veterinary Care command service (report class CareService). Every write
 * re-checks the active vet-owner link (US07/US09/US10/US11/US19/US20) and the
 * animal's ownership through the Livestock public query API.
 */
@Service
public class CareService {

    private final AppointmentRepository appointments;
    private final CareRecordRepository records;
    private final LinkingQueryService linkingQueries;
    private final LivestockQueries livestockQueries;
    private final DomainEventDispatcher events;

    public CareService(AppointmentRepository appointments, CareRecordRepository records,
                       LinkingQueryService linkingQueries, LivestockQueries livestockQueries,
                       DomainEventDispatcher events) {
        this.appointments = appointments;
        this.records = records;
        this.linkingQueries = linkingQueries;
        this.livestockQueries = livestockQueries;
        this.events = events;
    }

    public record TreatmentInput(
            @NotBlank(message = "obligatorio") String description,
            @NotNull(message = "obligatorio") @PastOrPresent(message = "no puede ser futura")
            LocalDate appliedDate) {
    }

    public record VaccinationInput(
            @NotBlank(message = "obligatorio")
            @Size(max = 150, message = "máximo 150 caracteres") String name,
            @NotNull(message = "obligatorio") @PastOrPresent(message = "no puede ser futura")
            LocalDate appliedDate) {
    }

    // ------------------------------------------------------------ scheduling

    /** US07: schedule a visit for an animal of a linked farmer. */
    @Transactional
    public VeterinaryAppointment scheduleVisit(UUID veterinarianId, UUID animalId, Instant scheduledAt,
                                               String reason) {
        Animal animal = requireAuthorizedAnimal(veterinarianId, animalId);
        VeterinaryAppointment appointment = VeterinaryAppointment.scheduleVisit(
                animal.getId(), animal.getOwnerId(), veterinarianId, scheduledAt, reason, Instant.now());
        appointments.save(appointment);
        events.publish(new VeterinaryAppointment.VisitScheduled(appointment.getId(), animal.getId(),
                animal.getOwnerId(), veterinarianId, scheduledAt, appointment.getType()));
        return appointment;
    }

    /** US12: schedule a follow-up control originating from an existing attention. */
    @Transactional
    public VeterinaryAppointment scheduleFollowUp(UUID veterinarianId, UUID attentionId, Instant scheduledAt,
                                                  String reason) {
        CareRecord record = records.findById(attentionId)
                .orElseThrow(() -> DomainException.notFound("Atención no encontrada"));
        requireLink(veterinarianId, record.getOwnerId());
        VeterinaryAppointment appointment = VeterinaryAppointment.scheduleFollowUp(
                record.getId(), record.getAnimalId(), record.getOwnerId(), veterinarianId,
                scheduledAt, reason, Instant.now());
        appointments.save(appointment);
        events.publish(new VeterinaryAppointment.VisitScheduled(appointment.getId(), record.getAnimalId(),
                record.getOwnerId(), veterinarianId, scheduledAt, appointment.getType()));
        return appointment;
    }

    /** Completes/cancels an assigned scheduled visit (idempotent). */
    @Transactional
    public VeterinaryAppointment changeVisitStatus(UUID veterinarianId, UUID appointmentId,
                                                   VeterinaryAppointment.Status newStatus) {
        VeterinaryAppointment appointment = appointments.findById(appointmentId)
                .orElseThrow(() -> DomainException.notFound("Cita no encontrada"));
        appointment.changeStatus(newStatus, veterinarianId, Instant.now());
        return appointments.save(appointment);
    }

    // ------------------------------------------------------------ attentions

    /** US09-US11 + US19: register an attention with its complementary records. */
    @Transactional
    public CareRecord registerAttention(UUID veterinarianId, UUID animalId, UUID visitId, String title,
                                        String description, LocalDate attentionDate,
                                        List<TreatmentInput> treatments, List<VaccinationInput> vaccinations,
                                        String instructions) {
        Animal animal = requireAuthorizedAnimal(veterinarianId, animalId);

        VeterinaryAppointment visit = null;
        if (visitId != null) {
            visit = appointments.findById(visitId)
                    .orElseThrow(() -> DomainException.notFound("Cita no encontrada"));
            if (!visit.getAnimalId().equals(animal.getId())) {
                throw DomainException.badRequest("VALIDATION_ERROR",
                        "visitId: la cita no corresponde al animal indicado");
            }
            if (!visit.isAssignedTo(veterinarianId)) {
                throw DomainException.forbidden("Solo el veterinario asignado puede registrar esta atención");
            }
        }

        CareRecord record = CareRecord.register(animal.getId(), animal.getOwnerId(), veterinarianId,
                visit == null ? null : visit.getId(), title, description, attentionDate, Instant.now());
        if (treatments != null) {
            treatments.forEach(input -> record.addTreatment(input.description(), input.appliedDate()));
        }
        if (vaccinations != null) {
            vaccinations.forEach(input -> record.addVaccination(input.name(), input.appliedDate()));
        }

        boolean hasInstructions = instructions != null && !instructions.isBlank();
        if (hasInstructions) {
            record.registerInstructions(instructions, veterinarianId, Instant.now());
        }
        records.save(record);

        if (visit != null) {
            // Registering the attention is what turns the scheduled visit into a performed one (US09).
            visit.changeStatus(VeterinaryAppointment.Status.COMPLETADA, veterinarianId, Instant.now());
            appointments.save(visit);
        }

        events.publish(new CareRecord.AttentionRegistered(record.getId(), record.getAnimalId(),
                record.getOwnerId(), veterinarianId, record.getTitle()));
        if (hasInstructions) {
            events.publish(new CareRecord.InstructionsRegistered(record.getId(), record.getAnimalId(),
                    record.getOwnerId(), veterinarianId));
        }
        return record;
    }

    @Transactional
    public CareRecord addTreatment(UUID veterinarianId, UUID attentionId, String description, LocalDate date) {
        CareRecord record = requireAuthorAndActiveLink(veterinarianId, attentionId);
        record.addTreatment(description, date);
        return records.save(record);
    }

    @Transactional
    public CareRecord addVaccination(UUID veterinarianId, UUID attentionId, String name, LocalDate date) {
        CareRecord record = requireAuthorAndActiveLink(veterinarianId, attentionId);
        record.addVaccination(name, date);
        return records.save(record);
    }

    /** US19: first instruction version + farmer notification event. */
    @Transactional
    public CareRecord createInstructions(UUID veterinarianId, UUID attentionId, String content) {
        CareRecord record = requireAuthorAndActiveLink(veterinarianId, attentionId);
        record.registerInstructions(content, veterinarianId, Instant.now());
        records.save(record);
        events.publish(new CareRecord.InstructionsRegistered(record.getId(), record.getAnimalId(),
                record.getOwnerId(), veterinarianId));
        return record;
    }

    /** US20: new instruction version, previous preserved. */
    @Transactional
    public CareRecord updateInstructions(UUID veterinarianId, UUID attentionId, String content) {
        CareRecord record = requireAuthorAndActiveLink(veterinarianId, attentionId);
        record.updateInstructions(content, veterinarianId, Instant.now());
        records.save(record);
        events.publish(new CareRecord.InstructionsUpdated(record.getId(), record.getAnimalId(),
                record.getOwnerId(), veterinarianId));
        return record;
    }

    // -------------------------------------------------------------- helpers

    private Animal requireAuthorizedAnimal(UUID veterinarianId, UUID animalId) {
        Animal animal = livestockQueries.findAnimal(animalId)
                .orElseThrow(() -> DomainException.notFound("Animal no encontrado"));
        requireLink(veterinarianId, animal.getOwnerId());
        return animal;
    }

    private void requireLink(UUID veterinarianId, UUID ownerId) {
        if (!linkingQueries.isVetAuthorizedFor(veterinarianId, ownerId)) {
            throw DomainException.forbidden(
                    "No tiene una vinculación activa con el propietario de este animal");
        }
    }

    private CareRecord requireAuthorAndActiveLink(UUID veterinarianId, UUID attentionId) {
        CareRecord record = records.findById(attentionId)
                .orElseThrow(() -> DomainException.notFound("Atención no encontrada"));
        if (!record.isAuthoredBy(veterinarianId)) {
            throw DomainException.forbidden("Solo el veterinario que registró la atención puede modificarla");
        }
        requireLink(veterinarianId, record.getOwnerId());
        return record;
    }
}
