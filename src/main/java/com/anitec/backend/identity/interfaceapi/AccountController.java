package com.anitec.backend.identity.interfaceapi;

import com.anitec.backend.identity.application.IdentityQueries;
import com.anitec.backend.identity.application.IdentityService;
import com.anitec.backend.identity.domain.Account;
import com.anitec.backend.shared.domain.Role;
import com.anitec.backend.shared.infrastructure.security.CurrentUser;
import com.anitec.backend.shared.interfaceapi.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** Current account profile (endpoints #7-#8: GET/PUT /api/v1/account/me). */
@RestController
@RequestMapping("/api/v1/account")
@Tag(name = "Cuenta", description = "Perfil de la cuenta autenticada")
public class AccountController {

    private final IdentityQueries identityQueries;
    private final IdentityService identityService;

    public AccountController(IdentityQueries identityQueries, IdentityService identityService) {
        this.identityQueries = identityQueries;
        this.identityService = identityService;
    }

    public record AccountProfile(
            UUID id, String name, String lastName, String email, String phone,
            String photoUrl, String address, String dni, Role role, String badge,
            boolean emailVerified, Instant createdAt) {

        static AccountProfile from(Account account) {
            return new AccountProfile(
                    account.getId(), account.getName(), account.getLastName(), account.getEmail(),
                    account.getPhone(), account.getPhotoUrl(), account.getAddress(), account.getDni(),
                    account.getRole(),
                    account.getRole() == Role.VETERINARIO ? "Veterinaria" : "Ganadero",
                    account.isVerified(), account.getCreatedAt());
        }
    }

    public record UpdateProfileRequest(
            @NotBlank(message = "obligatorio")
            @Size(max = 100, message = "máximo 100 caracteres") String name,
            @NotBlank(message = "obligatorio")
            @Size(max = 100, message = "máximo 100 caracteres") String lastName,
            String photoUrl,
            @Size(max = 255, message = "máximo 255 caracteres") String address,
            @Size(max = 15, message = "máximo 15 caracteres") String dni,
            @Size(max = 20, message = "máximo 20 caracteres") String phone) {
    }

    @GetMapping("/me")
    @Operation(summary = "Perfil de la cuenta autenticada (incluye badge de perfil)")
    public ResponseEntity<ApiResponse<AccountProfile>> me() {
        Account account = identityQueries.getIdentity(CurrentUser.id());
        return ResponseEntity.ok(ApiResponse.ok(AccountProfile.from(account)));
    }

    @PutMapping("/me")
    @Operation(summary = "Actualizar datos personales del perfil")
    public ResponseEntity<ApiResponse<AccountProfile>> update(@Valid @RequestBody UpdateProfileRequest request) {
        Account updated = identityService.updateProfile(CurrentUser.id(),
                new IdentityService.ProfileUpdate(request.name(), request.lastName(), request.photoUrl(),
                        request.address(), request.dni(), request.phone()));
        return ResponseEntity.ok(ApiResponse.ok("Perfil actualizado", AccountProfile.from(updated)));
    }
}
