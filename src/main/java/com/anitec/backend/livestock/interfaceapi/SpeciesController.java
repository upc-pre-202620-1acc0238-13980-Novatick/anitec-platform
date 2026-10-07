package com.anitec.backend.livestock.interfaceapi;

import com.anitec.backend.livestock.application.LivestockQueries;
import com.anitec.backend.shared.interfaceapi.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Species catalog read endpoint (requirements.md entity, any authenticated user). */
@RestController
@RequestMapping("/api/v1/species")
@Tag(name = "Especies", description = "Catálogo de especies")
public class SpeciesController {

    private final LivestockQueries livestockQueries;

    public SpeciesController(LivestockQueries livestockQueries) {
        this.livestockQueries = livestockQueries;
    }

    @GetMapping
    @Operation(summary = "Listar especies registradas")
    public ResponseEntity<ApiResponse<List<LivestockQueries.SpeciesView>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(livestockQueries.listSpecies()));
    }
}
