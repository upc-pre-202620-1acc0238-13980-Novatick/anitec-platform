package com.anitec.backend.livestock.interfaceapi;

import com.anitec.backend.identity.application.IdentityQueries;
import com.anitec.backend.livestock.application.LivestockQueries;
import com.anitec.backend.livestock.application.LivestockService;
import com.anitec.backend.livestock.domain.Animal;
import com.anitec.backend.livestock.domain.Farm;
import com.anitec.backend.linking.application.LinkingQueryService;
import com.anitec.backend.shared.infrastructure.security.CurrentUser;
import com.anitec.backend.shared.interfaceapi.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Farm endpoints (spec endpoints #14-#16 and #23). */
@RestController
@RequestMapping("/api/v1/farms")
@Tag(name = "Fincas", description = "Gestión de fincas del ganadero")
public class FarmController {

    private final LivestockService livestockService;
    private final LivestockQueries livestockQueries;
    private final LinkingQueryService linkingQueries;

    public FarmController(LivestockService livestockService, LivestockQueries livestockQueries,
                          LinkingQueryService linkingQueries) {
        this.livestockService = livestockService;
        this.livestockQueries = livestockQueries;
        this.linkingQueries = linkingQueries;
    }

    public record CreateFarmRequest(
            @NotBlank(message = "obligatorio") @Size(max = 150, message = "máximo 150 caracteres") String name,
            @Size(max = 255, message = "máximo 255 caracteres") String location) {
    }

    public record FarmResponse(UUID id, String name, String location, UUID ownerId, Instant createdAt) {
        static FarmResponse from(Farm farm) {
            return new FarmResponse(farm.getId(), farm.getName(), farm.getLocation(),
                    farm.getOwnerId(), farm.getCreatedAt());
        }
    }

    public record FarmAnimalsResponse(long totalActive, List<LivestockQueries.AnimalListItem> content) {
    }

    private Collection<UUID> scope() {
        UUID me = CurrentUser.id();
        return switch (CurrentUser.role()) {
            case GANADERO -> List.of(me);
            case VETERINARIO -> linkingQueries.activeFarmerIds(me);
            default -> Set.of();
        };
    }

    @PostMapping
    @Operation(summary = "Crear una finca (Ganadero)")
    public ResponseEntity<ApiResponse<FarmResponse>> create(@Valid @RequestBody CreateFarmRequest request) {
        Farm farm = livestockService.createFarm(CurrentUser.id(), request.name(), request.location());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Finca creada", FarmResponse.from(farm)));
    }

    @GetMapping
    @Operation(summary = "Fincas visibles: propias (ganadero) o de ganaderos vinculados (veterinario)")
    public ResponseEntity<ApiResponse<List<FarmResponse>>> list() {
        Collection<UUID> scope = scope();
        List<FarmResponse> content = scope.stream()
                .flatMap(ownerId -> livestockQueries.farmsOf(ownerId).stream())
                .map(FarmResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(content));
    }

    @GetMapping("/{farmId}")
    @Operation(summary = "Detalle de una finca (propietario o veterinario vinculado)")
    public ResponseEntity<ApiResponse<FarmResponse>> detail(@PathVariable UUID farmId) {
        Farm farm = livestockQueries.requireFarmInScope(farmId, scope());
        return ResponseEntity.ok(ApiResponse.ok(FarmResponse.from(farm)));
    }

    @GetMapping("/{farmId}/animals")
    @Operation(summary = "Animales de una finca con filtros (propietario o veterinario vinculado)")
    public ResponseEntity<ApiResponse<FarmAnimalsResponse>> animals(
            @PathVariable UUID farmId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID species,
            @RequestParam(required = false) Animal.Status status) {
        Collection<UUID> scope = scope();
        livestockQueries.requireFarmInScope(farmId, scope);
        List<LivestockQueries.AnimalListItem> content =
                livestockQueries.search(scope, farmId, species, status, search);
        return ResponseEntity.ok(ApiResponse.ok(
                new FarmAnimalsResponse(livestockQueries.countActive(scope), content)));
    }
}
