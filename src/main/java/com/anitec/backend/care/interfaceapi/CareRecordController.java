package com.anitec.backend.care.interfaceapi;

import com.anitec.backend.care.application.CareQueries;
import com.anitec.backend.care.application.CareService;
import com.anitec.backend.care.application.CareService.TreatmentInput;
import com.anitec.backend.care.application.CareService.VaccinationInput;
import com.anitec.backend.linking.application.LinkingQueryService;
import com.anitec.backend.shared.domain.Role;
import com.anitec.backend.shared.infrastructure.security.CurrentUser;
import com.anitec.backend.shared.interfaceapi.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Attention/clinical record endpoints (spec #43-#49, US09-US11, US19-US21). */
@RestController
@RequestMapping("/api/v1/attentions")
@Tag(name = "Atenciones", description = "Atenciones veterinarias, tratamientos, vacunaciones e indicaciones")
public class CareRecordController {

    private final CareService careService;
    private final CareQueries careQueries;
    private final LinkingQueryService linkingQueries;

    public CareRecordController(CareService careService, CareQueries careQueries,
                                LinkingQueryService linkingQueries) {
        this.careService = careService;
        this.careQueries = careQueries;
        this.linkingQueries = linkingQueries;
    }

    // ------------------------------------------------------------ request DTOs

    public record AttentionRequest(
            @NotNull(message = "obligatorio") UUID animalId,
            UUID visitId,
            @NotBlank(message = "obligatorio")
            @Size(max = 150, message = "máximo 150 caracteres") String title,
            String description,
            @PastOrPresent(message = "no puede ser futura") LocalDate attentionDate,
            List<@Valid TreatmentInput> treatments,
            List<@Valid VaccinationInput> vaccinations,
            String instructions) {
    }

    public record TreatmentRequest(
            @NotBlank(message = "obligatorio") String description,
            @NotNull(message = "obligatorio") @PastOrPresent(message = "no puede ser futura")
            LocalDate appliedDate) {
    }

    public record VaccinationRequest(
            @NotBlank(message = "obligatorio")
            @Size(max = 150, message = "máximo 150 caracteres") String name,
            @NotNull(message = "obligatorio") @PastOrPresent(message = "no puede ser futura")
            LocalDate appliedDate) {
    }

    public record InstructionsRequest(@NotBlank(message = "obligatorio") String content) {
    }

    // --------------------------------------------------------------- scopes

    private Collection<UUID> scope() {
        UUID me = CurrentUser.id();
        return switch (CurrentUser.role()) {
            case GANADERO -> List.of(me);
            case VETERINARIO -> linkingQueries.activeFarmerIds(me);
            default -> Set.of();
        };
    }

    private void requireVeterinarian() {
        if (CurrentUser.role() != Role.VETERINARIO) {
            throw com.anitec.backend.shared.domain.DomainException.forbidden(
                    "Esta operación es exclusiva del perfil veterinario");
        }
    }

    // ------------------------------------------------------------- endpoints

    @PostMapping
    @Operation(summary = "Registrar una atención veterinaria (US09-US11)")
    public ResponseEntity<ApiResponse<CareQueries.AttentionDetail>> register(
            @Valid @RequestBody AttentionRequest request) {
        requireVeterinarian();
        var record = careService.registerAttention(CurrentUser.id(), request.animalId(), request.visitId(),
                request.title(), request.description(), request.attentionDate(),
                request.treatments(), request.vaccinations(), request.instructions());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Atención registrada", careQueries.getAttention(record.getId())));
    }

    @GetMapping("/{attentionId}")
    @Operation(summary = "Detalle de una atención (propietario o veterinario vinculado)")
    public ResponseEntity<ApiResponse<CareQueries.AttentionDetail>> detail(@PathVariable UUID attentionId) {
        careQueries.requireAttentionInScope(attentionId, scope());
        return ResponseEntity.ok(ApiResponse.ok(careQueries.getAttention(attentionId)));
    }

    @PostMapping("/{attentionId}/treatments")
    @Operation(summary = "Agregar un tratamiento a una atención propia (US10)")
    public ResponseEntity<ApiResponse<Void>> addTreatment(
            @PathVariable UUID attentionId, @Valid @RequestBody TreatmentRequest request) {
        requireVeterinarian();
        careService.addTreatment(CurrentUser.id(), attentionId, request.description(), request.appliedDate());
        return ResponseEntity.ok(ApiResponse.ok("Tratamiento registrado"));
    }

    @PostMapping("/{attentionId}/vaccinations")
    @Operation(summary = "Agregar una vacunación a una atención propia (US11)")
    public ResponseEntity<ApiResponse<Void>> addVaccination(
            @PathVariable UUID attentionId, @Valid @RequestBody VaccinationRequest request) {
        requireVeterinarian();
        careService.addVaccination(CurrentUser.id(), attentionId, request.name(), request.appliedDate());
        return ResponseEntity.ok(ApiResponse.ok("Vacunación registrada"));
    }

    @PostMapping("/{attentionId}/instructions")
    @Operation(summary = "Registrar las indicaciones de cuidado (US19) y avisar al ganadero")
    public ResponseEntity<ApiResponse<CareQueries.InstructionView>> createInstructions(
            @PathVariable UUID attentionId, @Valid @RequestBody InstructionsRequest request) {
        requireVeterinarian();
        careService.createInstructions(CurrentUser.id(), attentionId, request.content());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Indicaciones registradas; se notificará al ganadero",
                        instructionView(attentionId)));
    }

    @PutMapping("/{attentionId}/instructions")
    @Operation(summary = "Modificar indicaciones generando una nueva versión (US20)")
    public ResponseEntity<ApiResponse<CareQueries.InstructionView>> updateInstructions(
            @PathVariable UUID attentionId, @Valid @RequestBody InstructionsRequest request) {
        requireVeterinarian();
        careService.updateInstructions(CurrentUser.id(), attentionId, request.content());
        return ResponseEntity.ok(ApiResponse.ok("Indicaciones actualizadas; se notificará al ganadero",
                instructionView(attentionId)));
    }

    @GetMapping("/{attentionId}/instructions")
    @Operation(summary = "Consultar las indicaciones vigentes (propietario o vinculado, US21)")
    public ResponseEntity<ApiResponse<CareQueries.InstructionView>> getInstructions(
            @PathVariable UUID attentionId) {
        var record = careQueries.requireAttentionInScope(attentionId, scope());
        CareQueries.InstructionView view = careQueries.instructionsFor(record);
        if (view == null) {
            return ResponseEntity.ok(ApiResponse.ok("No hay indicaciones registradas", null));
        }
        return ResponseEntity.ok(ApiResponse.ok(view));
    }

    private CareQueries.InstructionView instructionView(UUID attentionId) {
        var record = careQueries.requireAttentionInScope(attentionId, scope());
        return careQueries.instructionsFor(record);
    }
}
