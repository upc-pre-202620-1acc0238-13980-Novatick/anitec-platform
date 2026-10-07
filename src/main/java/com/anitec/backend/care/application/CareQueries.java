package com.anitec.backend.care.application;

import com.anitec.backend.care.domain.AppointmentRepository;
import com.anitec.backend.care.domain.CareRecord;
import com.anitec.backend.care.domain.CareRecordRepository;
import com.anitec.backend.care.domain.VeterinaryAppointment;
import com.anitec.backend.identity.application.IdentityQueries;
import com.anitec.backend.livestock.application.LivestockQueries;
import com.anitec.backend.shared.domain.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Veterinary Care read model (report class CareQueries): agenda, animal
 * history and instructions. Name/animal enrichment goes through the public
 * query APIs of Identity and Livestock (context map section 5.2).
 */
@Service
@Transactional(readOnly = true)
public class CareQueries {

    private final AppointmentRepository appointments;
    private final CareRecordRepository records;
    private final IdentityQueries identityQueries;
    private final LivestockQueries livestockQueries;

    public CareQueries(AppointmentRepository appointments, CareRecordRepository records,
                       IdentityQueries identityQueries, LivestockQueries livestockQueries) {
        this.appointments = appointments;
        this.records = records;
        this.identityQueries = identityQueries;
        this.livestockQueries = livestockQueries;
    }

    // ------------------------------------------------------------- records

    public record AttentionSummary(UUID id, String title, String vetName, LocalDate date, String summary) {
    }

    public record TreatmentView(String description, LocalDate date) {
    }

    public record VaccinationView(String name, LocalDate date) {
    }

    public record InstructionView(String content, String authorName, Instant updatedAt) {
    }

    public record AttentionDetail(
            UUID id, UUID animalId, String title, String description, LocalDate attentionDate,
            UUID veterinarianId, String vetName, List<TreatmentView> treatments,
            List<VaccinationView> vaccinations, InstructionView instructions) {
    }

    public record AppointmentView(UUID id, UUID animalId, String animalName, UUID farmerId,
                                  String farmerName, String type, Instant scheduledAt, String status,
                                  String reason) {
    }

    // ------------------------------------------------------------- queries

    public List<AttentionSummary> recentForAnimal(UUID animalId, int limit) {
        return toSummaries(records.findByAnimalIdOrderByAttentionDateDesc(animalId).stream()
                .limit(limit).toList());
    }

    public long countForAnimal(UUID animalId) {
        return records.countByAnimalId(animalId);
    }

    /** US13: full history with treatments, vaccinations and current instructions. */
    public List<AttentionDetail> medicalHistory(UUID animalId) {
        List<CareRecord> found = records.findByAnimalIdOrderByAttentionDateDesc(animalId);
        Map<UUID, String> vets = identityQueries.displayNames(
                found.stream().map(CareRecord::getVeterinarianId).toList());
        return found.stream().map(record -> toDetail(record, vets)).toList();
    }

    public AttentionDetail getAttention(UUID attentionId) {
        CareRecord record = records.findById(attentionId)
                .orElseThrow(() -> DomainException.notFound("Atención no encontrada"));
        Map<UUID, String> vets = identityQueries.displayNames(List.of(record.getVeterinarianId()));
        return toDetail(record, vets);
    }

    /** Returns the attention when its owner is inside the caller's scope (403 otherwise). */
    public CareRecord requireAttentionInScope(UUID attentionId, Collection<UUID> ownerScope) {
        CareRecord record = records.findById(attentionId)
                .orElseThrow(() -> DomainException.notFound("Atención no encontrada"));
        if (ownerScope == null || !ownerScope.contains(record.getOwnerId())) {
            throw DomainException.forbidden("No tiene autorización sobre esta atención");
        }
        return record;
    }

    /** US21: current instructions of an attention already authorized by the caller scope. */
    public InstructionView instructionsFor(CareRecord record) {
        return record.currentInstructions()
                .map(version -> {
                    String author = identityQueries.displayNames(List.of(version.getAuthorId()))
                            .get(version.getAuthorId());
                    return new InstructionView(version.getContent(), author, version.getCreatedAt());
                })
                .orElse(null);
    }

    /** US08: agenda of the veterinarian for the requested period, ordered by date. */
    public List<AppointmentView> agenda(UUID veterinarianId, Instant from, Instant to) {
        List<VeterinaryAppointment> found =
                appointments.findByVeterinarianAndRange(veterinarianId, from, to);
        Map<UUID, String> farmers = identityQueries.displayNames(
                found.stream().map(VeterinaryAppointment::getOwnerId).toList());
        return found.stream()
                .map(appointment -> new AppointmentView(
                        appointment.getId(),
                        appointment.getAnimalId(),
                        livestockQueries.findAnimal(appointment.getAnimalId())
                                .map(animal -> animal.getName() == null
                                        ? animal.codeValue() : animal.getName()).orElse(null),
                        appointment.getOwnerId(),
                        farmers.getOrDefault(appointment.getOwnerId(), "Ganadero"),
                        appointment.getType().name(),
                        appointment.getScheduledAt(),
                        appointment.getStatus().name(),
                        appointment.getReason()))
                .toList();
    }

    public VeterinaryAppointment requireVisit(UUID appointmentId) {
        return appointments.findById(appointmentId)
                .orElseThrow(() -> DomainException.notFound("Cita no encontrada"));
    }

    // ------------------------------------------------------------- helpers

    private List<AttentionSummary> toSummaries(List<CareRecord> found) {
        Map<UUID, String> vets = identityQueries.displayNames(
                found.stream().map(CareRecord::getVeterinarianId).toList());
        return found.stream()
                .map(record -> new AttentionSummary(record.getId(), record.getTitle(),
                        vets.getOrDefault(record.getVeterinarianId(), "Veterinario"),
                        record.getAttentionDate(), summarize(record.getDescription())))
                .toList();
    }

    private AttentionDetail toDetail(CareRecord record, Map<UUID, String> names) {
        InstructionView instructions = record.currentInstructions()
                .map(version -> new InstructionView(version.getContent(),
                        identityQueries.displayNames(List.of(version.getAuthorId()))
                                .get(version.getAuthorId()),
                        version.getCreatedAt()))
                .orElse(null);
        return new AttentionDetail(
                record.getId(), record.getAnimalId(), record.getTitle(), record.getDescription(),
                record.getAttentionDate(), record.getVeterinarianId(),
                names.getOrDefault(record.getVeterinarianId(), "Veterinario"),
                record.getTreatments().stream()
                        .map(t -> new TreatmentView(t.getDescription(), t.getAppliedDate())).toList(),
                record.getVaccinations().stream()
                        .map(v -> new VaccinationView(v.getName(), v.getAppliedDate())).toList(),
                instructions);
    }

    private String summarize(String description) {
        if (description == null) {
            return null;
        }
        return description.length() <= 120 ? description : description.substring(0, 117) + "...";
    }
}
