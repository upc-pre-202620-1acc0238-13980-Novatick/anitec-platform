package com.anitec.backend.admin.interfaceapi;

import com.anitec.backend.admin.application.AdminService;
import com.anitec.backend.identity.application.IdentityQueries;
import com.anitec.backend.identity.domain.Account;
import com.anitec.backend.shared.domain.Role;
import com.anitec.backend.shared.infrastructure.security.CurrentUser;
import com.anitec.backend.shared.interfaceapi.ApiResponse;
import com.anitec.backend.subscriptions.application.SubscriptionService;
import com.anitec.backend.subscriptions.domain.Plan;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Admin endpoints (spec #52-#57, decision #16). Role ADMIN only (route rule). */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin", description = "Gestión de usuarios, catálogos, planes y auditoría")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    // ------------------------------------------------------------ request DTOs

    public record UserStatusRequest(@NotNull(message = "obligatorio") Account.Status status) {
    }

    public record SpeciesRequest(
            @NotBlank(message = "obligatorio")
            @Size(max = 100, message = "máximo 100 caracteres") String name,
            String description,
            String photoUrl) {
    }

    public record PlanCreateRequest(
            @NotBlank(message = "obligatorio")
            @Size(max = 50, message = "máximo 50 caracteres") String name,
            @NotNull(message = "obligatorio") Role profile,
            @NotNull(message = "obligatorio") Plan.PlanType type,
            @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal price,
            @Size(min = 3, max = 3, message = "debe tener 3 caracteres") String currency,
            @Size(max = 20, message = "máximo 20 caracteres") String billingPeriod,
            @NotNull(message = "obligatorio") @Min(value = 0, message = "no puede ser negativo")
            Integer capacityLimit,
            Boolean active) {
    }

    public record PlanUpdateRequest(
            @Size(max = 50, message = "máximo 50 caracteres") String name,
            @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal price,
            @Min(value = 0, message = "no puede ser negativo") Integer capacityLimit,
            Boolean active) {
    }

    // ------------------------------------------------------------- endpoints

    @GetMapping("/users")
    @Operation(summary = "Listar/buscar usuarios (paginado)")
    public ResponseEntity<ApiResponse<IdentityQueries.AccountPage>> users(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Role role,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
                adminService.searchUsers(search, role, page, size)));
    }

    @PatchMapping("/users/{userId}/status")
    @Operation(summary = "Activar o suspender una cuenta (audita la acción)")
    public ResponseEntity<ApiResponse<IdentityQueries.AccountSummary>> changeStatus(
            @PathVariable UUID userId, @Valid @RequestBody UserStatusRequest request) {
        var account = adminService.changeUserStatus(CurrentUser.id(), userId, request.status());
        return ResponseEntity.ok(ApiResponse.ok("Estado de la cuenta actualizado",
                IdentityQueries.AccountSummary.from(account)));
    }

    @PostMapping("/species")
    @Operation(summary = "Crear una especie en el catálogo")
    public ResponseEntity<ApiResponse<com.anitec.backend.livestock.application.LivestockQueries.SpeciesView>> createSpecies(
            @Valid @RequestBody SpeciesRequest request) {
        var species = adminService.createSpecies(CurrentUser.id(), request.name(),
                request.description(), request.photoUrl());
        return ResponseEntity.ok(ApiResponse.ok("Especie creada",
                new com.anitec.backend.livestock.application.LivestockQueries.SpeciesView(
                        species.getId(), species.getName(), species.getDescription(), species.getPhotoUrl())));
    }

    @PutMapping("/species/{speciesId}")
    @Operation(summary = "Actualizar una especie")
    public ResponseEntity<ApiResponse<com.anitec.backend.livestock.application.LivestockQueries.SpeciesView>> updateSpecies(
            @PathVariable UUID speciesId, @Valid @RequestBody SpeciesRequest request) {
        var species = adminService.updateSpecies(CurrentUser.id(), speciesId, request.name(),
                request.description(), request.photoUrl());
        return ResponseEntity.ok(ApiResponse.ok("Especie actualizada",
                new com.anitec.backend.livestock.application.LivestockQueries.SpeciesView(
                        species.getId(), species.getName(), species.getDescription(), species.getPhotoUrl())));
    }

    @DeleteMapping("/species/{speciesId}")
    @Operation(summary = "Eliminar una especie sin uso (409 si está en uso)")
    public ResponseEntity<ApiResponse<Void>> deleteSpecies(@PathVariable UUID speciesId) {
        adminService.deleteSpecies(CurrentUser.id(), speciesId);
        return ResponseEntity.ok(ApiResponse.ok("Especie eliminada"));
    }

    @GetMapping("/plans")
    @Operation(summary = "Listar todos los planes (ambos perfiles)")
    public ResponseEntity<ApiResponse<List<SubscriptionService.PlanView>>> plans() {
        return ResponseEntity.ok(ApiResponse.ok(adminService.allPlans()));
    }

    @PostMapping("/plans")
    @Operation(summary = "Crear un plan")
    public ResponseEntity<ApiResponse<SubscriptionService.PlanView>> createPlan(
            @Valid @RequestBody PlanCreateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Plan creado",
                adminService.createPlan(CurrentUser.id(), request.name(), request.profile(),
                        request.type(),
                        request.price() == null ? BigDecimal.ZERO : request.price(),
                        request.currency() == null ? "PEN" : request.currency(),
                        request.billingPeriod() == null ? "MENSUAL" : request.billingPeriod(),
                        request.capacityLimit(),
                        request.active() == null || request.active())));
    }

    @PutMapping("/plans/{planId}")
    @Operation(summary = "Actualizar un plan (el cambio de límite republica el límite a los suscriptores)")
    public ResponseEntity<ApiResponse<SubscriptionService.PlanView>> updatePlan(
            @PathVariable UUID planId, @Valid @RequestBody PlanUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Plan actualizado",
                adminService.updatePlan(CurrentUser.id(), planId, request.name(), request.price(),
                        request.capacityLimit(), request.active())));
    }

    @GetMapping("/audit-log")
    @Operation(summary = "Bitácora de acciones administrativas (paginada)")
    public ResponseEntity<ApiResponse<AdminService.AuditPage>> auditLog(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(adminService.auditLog(page, size)));
    }
}
