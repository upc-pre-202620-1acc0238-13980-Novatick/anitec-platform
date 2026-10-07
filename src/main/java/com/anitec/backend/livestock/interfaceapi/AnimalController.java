package com.anitec.backend.livestock.interfaceapi;

import com.anitec.backend.care.application.CareQueries;
import com.anitec.backend.identity.application.IdentityQueries;
import com.anitec.backend.livestock.application.LivestockQueries;
import com.anitec.backend.livestock.application.LivestockService;
import com.anitec.backend.livestock.domain.Animal;
import com.anitec.backend.livestock.domain.AnimalObservation;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Animal inventory endpoints (spec endpoints #21-#31). Read scopes are
 * composed here at the interface layer: a ganadero sees his own animals, a
 * linked veterinario sees the animals of his linked farmers, nobody else.
 */
@RestController
@RequestMapping("/api/v1/animals")
@Tag(name = "Animales", description = "Inventario de ganado, observaciones y capacidad")
public class AnimalController {

    private static final int RECENT_LIMIT = 5;

    private final LivestockService livestockService;
    private final LivestockQueries livestockQueries;
    private final LinkingQueryService linkingQueries;
    private final CareQueries careQueries;
    private final IdentityQueries identityQueries;

    public AnimalController(LivestockService livestockService, LivestockQueries livestockQueries,
                            LinkingQueryService linkingQueries, CareQueries careQueries,
                            IdentityQueries identityQueries) {
        this.livestockService = livestockService;
        this.livestockQueries = livestockQueries;
        this.linkingQueries = linkingQueries;
        this.careQueries = careQueries;
        this.identityQueries = identityQueries;
    }

    // ---------------------------------------------------------- request DTOs

    public record AnimalDataRequest(
            @NotNull(message = "obligatorio") UUID farmId,
            @NotBlank(message = "obligatorio") @Size(max = 50, message = "máximo 50 caracteres") String code,
            @NotNull(message = "obligatorio") UUID speciesId,
            @NotNull(message = "obligatorio") Animal.Sex sex,
            @Size(max = 100, message = "máximo 100 caracteres") String name,
            @Size(max = 100, message = "máximo 100 caracteres") String breed,
            @PastOrPresent(message = "no puede ser futura") LocalDate birthDate,
            String photoUrl) {
    }

    public record AnimalUpdateRequest(
            @NotBlank(message = "obligatorio") @Size(max = 50, message = "máximo 50 caracteres") String code,
            @NotNull(message = "obligatorio") UUID speciesId,
            @NotNull(message = "obligatorio") Animal.Sex sex,
            @Size(max = 100, message = "máximo 100 caracteres") String name,
            @Size(max = 100, message = "máximo 100 caracteres") String breed,
            @PastOrPresent(message = "no puede ser futura") LocalDate birthDate,
            String photoUrl) {
    }

    public record StatusRequest(@NotNull(message = "obligatorio") Animal.Status status) {
    }

    public record ObservationRequest(@NotBlank(message = "obligatorio") String text) {
    }

    // ------------------------------------------------------- response DTOs

    public record InventoryResponse(long totalActive, List<LivestockQueries.AnimalListItem> content) {
    }

    public record ObservationResponse(UUID id, String text, String authorName, LocalDate date) {
    }

    public record AnimalDetailResponse(
            UUID id, String code, String name, String species, String sex, String breed,
            LocalDate birthDate, Animal.Status status, UUID farmId, String farmName,
            long observationsCount, List<ObservationResponse> recentObservations,
            long attentionsCount, List<CareQueries.AttentionSummary> recentAttentions) {
    }

    public record StatusResponse(UUID id, Animal.Status status) {
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

    // ----------------------------------------------------------- endpoints

    @PostMapping
    @Operation(summary = "Registrar un animal (Ganadero, respeta la capacidad de su plan)")
    public ResponseEntity<ApiResponse<LivestockQueries.AnimalListItem>> register(
            @Valid @RequestBody AnimalDataRequest request) {
        Animal animal = livestockService.registerAnimal(CurrentUser.id(), request.farmId(), request.code(),
                request.speciesId(), request.sex(), request.name(), request.breed(),
                request.birthDate(), request.photoUrl());
        LivestockQueries.AnimalListItem item = new LivestockQueries.AnimalListItem(
                animal.getId(), animal.codeValue(), animal.getName(),
                livestockQueries.speciesName(animal.getSpeciesId()), animal.getSpeciesId(),
                animal.getStatus(), animal.getFarmId(), animal.getOwnerId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Animal registrado", item));
    }

    @GetMapping
    @Operation(summary = "Inventario con filtros (search, species, status, farmId)")
    public ResponseEntity<ApiResponse<InventoryResponse>> inventory(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID species,
            @RequestParam(required = false) Animal.Status status,
            @RequestParam(required = false) UUID farmId) {
        Collection<UUID> scope = scope();
        List<LivestockQueries.AnimalListItem> content =
                livestockQueries.search(scope, farmId, species, status, search);
        return ResponseEntity.ok(ApiResponse.ok(
                new InventoryResponse(livestockQueries.countActive(scope), content)));
    }

    @GetMapping("/capacity")
    @Operation(summary = "Capacidad de inventario del ganadero autenticado")
    public ResponseEntity<ApiResponse<Map<String, Object>>> capacity() {
        var capacity = livestockQueries.capacityOf(CurrentUser.id());
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "ownerId", capacity.getOwnerId(),
                "allowedAnimals", capacity.getAllowedAnimals(),
                "activeAnimals", capacity.getActiveAnimals(),
                "lastPlanRevision", capacity.getLastPlanRevision())));
    }

    @GetMapping("/{animalId}")
    @Operation(summary = "Ficha completa del animal con observaciones y atenciones recientes")
    public ResponseEntity<ApiResponse<AnimalDetailResponse>> detail(@PathVariable UUID animalId) {
        Collection<UUID> scope = scope();
        Animal animal = livestockQueries.requireAnimalInScope(animalId, scope);

        List<AnimalObservation> recent = animal.getObservations().stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .limit(RECENT_LIMIT)
                .toList();
        Map<UUID, String> names = identityQueries.displayNames(
                recent.stream().map(AnimalObservation::getAuthorId).toList());

        List<ObservationResponse> observations = recent.stream()
                .map(observation -> new ObservationResponse(observation.getId(), observation.getText(),
                        names.get(observation.getAuthorId()),
                        LocalDate.ofInstant(observation.getCreatedAt(), java.time.ZoneOffset.UTC)))
                .toList();

        AnimalDetailResponse detail = new AnimalDetailResponse(
                animal.getId(), animal.codeValue(), animal.getName(),
                livestockQueries.speciesName(animal.getSpeciesId()),
                animal.getSex().name(), animal.getBreed(), animal.getBirthDate(), animal.getStatus(),
                animal.getFarmId(), livestockQueries.farmName(animal.getFarmId()),
                animal.getObservations().size(), observations,
                careQueries.countForAnimal(animal.getId()),
                careQueries.recentForAnimal(animal.getId(), RECENT_LIMIT));
        return ResponseEntity.ok(ApiResponse.ok(detail));
    }

    @PutMapping("/{animalId}")
    @Operation(summary = "Actualizar datos editables del animal (Ganadero propietario)")
    public ResponseEntity<ApiResponse<LivestockQueries.AnimalListItem>> update(
            @PathVariable UUID animalId, @Valid @RequestBody AnimalUpdateRequest request) {
        Animal animal = livestockService.updateAnimal(CurrentUser.id(), animalId, request.code(),
                request.speciesId(), request.sex(), request.name(), request.breed(),
                request.birthDate(), request.photoUrl());
        LivestockQueries.AnimalListItem item = new LivestockQueries.AnimalListItem(
                animal.getId(), animal.codeValue(), animal.getName(),
                livestockQueries.speciesName(animal.getSpeciesId()), animal.getSpeciesId(),
                animal.getStatus(), animal.getFarmId(), animal.getOwnerId());
        return ResponseEntity.ok(ApiResponse.ok("Animal actualizado", item));
    }

    @DeleteMapping("/{animalId}")
    @Operation(summary = "Dar de baja un animal (soft delete, libera cupo, idempotente)")
    public ResponseEntity<ApiResponse<StatusResponse>> deactivate(@PathVariable UUID animalId) {
        Animal animal = livestockService.deactivateAnimal(CurrentUser.id(), animalId);
        return ResponseEntity.ok(ApiResponse.ok("Animal dado de baja",
                new StatusResponse(animal.getId(), animal.getStatus())));
    }

    @PatchMapping("/{animalId}/status")
    @Operation(summary = "Cambiar estado del animal (ACTIVO requiere cupo disponible)")
    public ResponseEntity<ApiResponse<StatusResponse>> changeStatus(
            @PathVariable UUID animalId, @Valid @RequestBody StatusRequest request) {
        Animal animal = livestockService.changeAnimalStatus(CurrentUser.id(), animalId, request.status());
        return ResponseEntity.ok(ApiResponse.ok("Estado actualizado",
                new StatusResponse(animal.getId(), animal.getStatus())));
    }

    @PostMapping("/{animalId}/observations")
    @Operation(summary = "Registrar una observación (solo el ganadero propietario)")
    public ResponseEntity<ApiResponse<ObservationResponse>> addObservation(
            @PathVariable UUID animalId, @Valid @RequestBody ObservationRequest request) {
        AnimalObservation observation = livestockService.addObservation(
                CurrentUser.id(), animalId, request.text());
        ObservationResponse response = new ObservationResponse(observation.getId(), observation.getText(),
                identityQueries.displayNames(List.of(observation.getAuthorId()))
                        .get(observation.getAuthorId()),
                LocalDate.ofInstant(observation.getCreatedAt(), java.time.ZoneOffset.UTC));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Observación registrada", response));
    }

    @GetMapping("/{animalId}/observations")
    @Operation(summary = "Historial completo de observaciones (propietario o veterinario vinculado)")
    public ResponseEntity<ApiResponse<List<ObservationResponse>>> observations(@PathVariable UUID animalId) {
        Animal animal = livestockQueries.requireAnimalInScope(animalId, scope());
        Map<UUID, String> names = identityQueries.displayNames(
                animal.getObservations().stream().map(AnimalObservation::getAuthorId).toList());
        List<ObservationResponse> content = animal.getObservations().stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .map(observation -> new ObservationResponse(observation.getId(), observation.getText(),
                        names.get(observation.getAuthorId()),
                        LocalDate.ofInstant(observation.getCreatedAt(), java.time.ZoneOffset.UTC)))
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(content));
    }

    @GetMapping("/{animalId}/medical-history")
    @Operation(summary = "Historial veterinario completo del animal (US13)")
    public ResponseEntity<ApiResponse<List<CareQueries.AttentionDetail>>> medicalHistory(
            @PathVariable UUID animalId) {
        livestockQueries.requireAnimalInScope(animalId, scope());
        return ResponseEntity.ok(ApiResponse.ok(careQueries.medicalHistory(animalId)));
    }
}
