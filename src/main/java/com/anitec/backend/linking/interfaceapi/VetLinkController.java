package com.anitec.backend.linking.interfaceapi;

import com.anitec.backend.identity.application.IdentityQueries;
import com.anitec.backend.livestock.application.LivestockQueries;
import com.anitec.backend.linking.application.LinkingQueryService;
import com.anitec.backend.linking.application.LinkingService;
import com.anitec.backend.linking.domain.VeterinaryLink;
import com.anitec.backend.shared.domain.DomainException;
import com.anitec.backend.shared.domain.Role;
import com.anitec.backend.shared.infrastructure.security.CurrentUser;
import com.anitec.backend.shared.interfaceapi.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Active links endpoints (spec #35-#38, US17-US18). */
@RestController
@RequestMapping("/api/v1/vet-links")
@Tag(name = "Vinculaciones", description = "Vinculaciones activas ganadero-veterinario")
public class VetLinkController {

    private final LinkingService linkingService;
    private final LinkingQueryService linkingQueries;
    private final LivestockQueries livestockQueries;

    public VetLinkController(LinkingService linkingService, LinkingQueryService linkingQueries,
                             LivestockQueries livestockQueries) {
        this.linkingService = linkingService;
        this.linkingQueries = linkingQueries;
        this.livestockQueries = livestockQueries;
    }

    // ------------------------------------------------------------ responses

    public record FarmCount(UUID farmId, String farmName, long animalsCount) {
    }

    public record FarmerEntry(UUID farmerId, String farmerName, long animalsCount, List<FarmCount> farms) {
    }

    public record MyFarmersResponse(long activeLinksCount, List<FarmerEntry> farmers) {
    }

    public record LinksResponse(long activeLinksCount, List<LinkingQueryService.LinkView> links) {
    }

    public record CapacityResponse(UUID vetId, int allowedRanchers, int activeLinks, long lastPlanRevision) {
    }

    // -------------------------------------------------------------- endpoints

    @GetMapping("/my-farmers")
    @Operation(summary = "Ganaderos vinculados con sus fincas y animales (veterinario, spec #35)")
    public ResponseEntity<ApiResponse<MyFarmersResponse>> myFarmers() {
        requireVeterinarian();
        UUID vetId = CurrentUser.id();
        List<LinkingQueryService.LinkView> links = linkingQueries.activeLinksAsVeterinarian(vetId);

        List<FarmerEntry> farmers = links.stream().map(link -> {
            List<FarmCount> farms = livestockQueries.farmsOf(link.farmerId()).stream()
                    .map(farm -> new FarmCount(farm.getId(), farm.getName(),
                            livestockQueries.countAnimalsByFarm(farm.getId())))
                    .toList();
            return new FarmerEntry(link.farmerId(), link.farmerName(),
                    livestockQueries.countAnimalsByOwner(link.farmerId()), farms);
        }).toList();

        return ResponseEntity.ok(ApiResponse.ok(new MyFarmersResponse(
                linkingQueries.activeLinksCountAsVeterinarian(vetId), farmers)));
    }

    @GetMapping
    @Operation(summary = "Mis vinculaciones activas (ambos perfiles, US17)")
    public ResponseEntity<ApiResponse<LinksResponse>> links() {
        UUID me = CurrentUser.id();
        Role role = CurrentUser.role();
        List<LinkingQueryService.LinkView> content = switch (role) {
            case GANADERO -> linkingQueries.activeLinksAsFarmer(me);
            case VETERINARIO -> linkingQueries.activeLinksAsVeterinarian(me);
            default -> List.of();
        };
        return ResponseEntity.ok(ApiResponse.ok(new LinksResponse(content.size(), content)));
    }

    @DeleteMapping("/{linkId}")
    @Operation(summary = "Revocar el acceso de un veterinario (solo el ganadero, US18)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> revoke(@PathVariable UUID linkId) {
        VeterinaryLink link = linkingService.revokeLink(CurrentUser.id(), linkId);
        return ResponseEntity.ok(ApiResponse.ok("Vinculación revocada", Map.of(
                "linkId", link.getId(),
                "status", link.getStatus().name(),
                "revokedAt", String.valueOf(link.getRevokedAt()))));
    }

    @GetMapping("/capacity")
    @Operation(summary = "Capacidad de vinculaciones del veterinario autenticado")
    public ResponseEntity<ApiResponse<CapacityResponse>> capacity() {
        requireVeterinarian();
        var capacity = linkingQueries.capacityOf(CurrentUser.id());
        return ResponseEntity.ok(ApiResponse.ok(new CapacityResponse(
                capacity.getVetId(), capacity.getAllowedRanchers(), capacity.getActiveLinks(),
                capacity.getLastPlanRevision())));
    }

    private void requireVeterinarian() {
        if (CurrentUser.role() != Role.VETERINARIO) {
            throw DomainException.forbidden("Esta operación es exclusiva del perfil veterinario");
        }
    }
}
