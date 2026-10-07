package com.anitec.backend.care.interfaceapi;

import com.anitec.backend.care.application.CareQueries;
import com.anitec.backend.care.application.CareService;
import com.anitec.backend.care.domain.VeterinaryAppointment;
import com.anitec.backend.shared.domain.DomainException;
import com.anitec.backend.shared.domain.Role;
import com.anitec.backend.shared.infrastructure.security.CurrentUser;
import com.anitec.backend.shared.interfaceapi.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** Visit scheduling endpoints (spec #39-#42, US07/US08/US12). */
@RestController
@RequestMapping("/api/v1/visits")
@Tag(name = "Visitas", description = "Agenda de visitas y controles veterinarios")
public class AppointmentController {

    private final CareService careService;
    private final CareQueries careQueries;

    public AppointmentController(CareService careService, CareQueries careQueries) {
        this.careService = careService;
        this.careQueries = careQueries;
    }

    public record ScheduleVisitRequest(
            @NotNull(message = "obligatorio") UUID animalId,
            @NotNull(message = "obligatorio") @Future(message = "debe ser futura") Instant scheduledAt,
            @Size(max = 255, message = "máximo 255 caracteres") String reason) {
    }

    public record FollowUpRequest(
            @NotNull(message = "obligatorio") UUID attentionId,
            @NotNull(message = "obligatorio") @Future(message = "debe ser futura") Instant scheduledAt,
            @Size(max = 255, message = "máximo 255 caracteres") String reason) {
    }

    public record StatusRequest(@NotNull(message = "obligatorio") VeterinaryAppointment.Status status) {
    }

    public record VisitResponse(UUID id, UUID animalId, UUID farmerId, String type, Instant scheduledAt,
                                String status, String reason) {
        static VisitResponse from(VeterinaryAppointment appointment) {
            return new VisitResponse(appointment.getId(), appointment.getAnimalId(), appointment.getOwnerId(),
                    appointment.getType().name(), appointment.getScheduledAt(),
                    appointment.getStatus().name(), appointment.getReason());
        }
    }

    @PostMapping("/schedule")
    @Operation(summary = "Programar una visita (solo veterinario con vinculación activa, US07)")
    public ResponseEntity<ApiResponse<VisitResponse>> schedule(@Valid @RequestBody ScheduleVisitRequest request) {
        requireVeterinarian();
        VeterinaryAppointment appointment = careService.scheduleVisit(CurrentUser.id(), request.animalId(),
                request.scheduledAt(), request.reason());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Visita programada", VisitResponse.from(appointment)));
    }

    @PostMapping("/follow-up")
    @Operation(summary = "Programar un control posterior a una atención (US12)")
    public ResponseEntity<ApiResponse<VisitResponse>> followUp(@Valid @RequestBody FollowUpRequest request) {
        requireVeterinarian();
        VeterinaryAppointment appointment = careService.scheduleFollowUp(CurrentUser.id(),
                request.attentionId(), request.scheduledAt(), request.reason());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Control programado", VisitResponse.from(appointment)));
    }

    @GetMapping
    @Operation(summary = "Agenda del veterinario por período (por defecto próximos 30 días, US08)")
    public ResponseEntity<ApiResponse<List<CareQueries.AppointmentView>>> agenda(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        requireVeterinarian();
        Instant start = from == null ? Instant.now() : from;
        Instant end = to == null ? start.plus(30, ChronoUnit.DAYS) : to;
        return ResponseEntity.ok(ApiResponse.ok(careQueries.agenda(CurrentUser.id(), start, end)));
    }

    @PatchMapping("/{visitId}/status")
    @Operation(summary = "Completar o cancelar una cita asignada (idempotente)")
    public ResponseEntity<ApiResponse<VisitResponse>> changeStatus(
            @PathVariable UUID visitId, @Valid @RequestBody StatusRequest request) {
        requireVeterinarian();
        if (request.status() == VeterinaryAppointment.Status.PROGRAMADA) {
            throw DomainException.badRequest("VALIDATION_ERROR",
                    "status: solo COMPLETADA o CANCELADA pueden aplicarse a una cita programada");
        }
        VeterinaryAppointment appointment = careService.changeVisitStatus(
                CurrentUser.id(), visitId, request.status());
        return ResponseEntity.ok(ApiResponse.ok("Cita actualizada", VisitResponse.from(appointment)));
    }

    private void requireVeterinarian() {
        if (CurrentUser.role() != Role.VETERINARIO) {
            throw DomainException.forbidden("Esta operación es exclusiva del perfil veterinario");
        }
    }
}
