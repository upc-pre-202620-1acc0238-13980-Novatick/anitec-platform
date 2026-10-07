package com.anitec.backend.identity.interfaceapi;

import com.anitec.backend.identity.application.IdentityService;
import com.anitec.backend.shared.domain.Role;
import com.anitec.backend.shared.interfaceapi.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints (spec section 8.2 and endpoints #1-#6).
 * All bodies/responses use the standard envelope (decision #8).
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Registro, verificación de correo y sesiones")
public class AuthController {

    private final IdentityService identityService;

    public AuthController(IdentityService identityService) {
        this.identityService = identityService;
    }

    // ------------------------------------------------------------- requests

    public record RegisterRequest(
            @NotBlank(message = "obligatorio")
            @Size(max = 100, message = "máximo 100 caracteres") String name,
            @NotBlank(message = "obligatorio")
            @Size(max = 100, message = "máximo 100 caracteres") String lastName,
            @NotBlank(message = "obligatorio")
            @Size(max = 150, message = "máximo 150 caracteres")
            @Email(message = "correo inválido") String email,
            @NotBlank(message = "obligatorio")
            @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*[0-9])(?=.*[^A-Za-z0-9\\s]).{8,}$",
                    message = "mínimo 8 caracteres con una mayúscula, una minúscula, un número y un carácter especial")
            String password,
            @NotNull(message = "obligatorio") Role role) {
    }

    public record VerifyRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 6, max = 6, message = "debe tener 6 dígitos") String code) {
    }

    public record ResendCodeRequest(@NotBlank @Email String email) {
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    // ------------------------------------------------------------- endpoints

    @PostMapping("/register")
    @Operation(summary = "Registrar cuenta (Ganadero o Veterinario) y enviar código de verificación")
    public ResponseEntity<ApiResponse<IdentityService.RegisterResult>> register(
            @Valid @RequestBody RegisterRequest request) {
        IdentityService.RegisterResult result = identityService.register(
                new IdentityService.RegisterCommand(
                        request.name(), request.lastName(), request.email(), request.password(), request.role()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Cuenta creada. Verifique su correo electrónico con el código enviado.",
                        result));
    }

    @PostMapping("/verify")
    @Operation(summary = "Verificar correo con el código de 6 dígitos (válido 10 minutos)")
    public ResponseEntity<ApiResponse<Void>> verify(@Valid @RequestBody VerifyRequest request) {
        identityService.verifyEmail(request.email(), request.code());
        return ResponseEntity.ok(ApiResponse.ok("Correo verificado. Ya puede iniciar sesión."));
    }

    @PostMapping("/resend-code")
    @Operation(summary = "Reenviar código de verificación (máx. 5 reenvíos, cada 60 s)")
    public ResponseEntity<ApiResponse<IdentityService.EmailDelivery>> resendCode(
            @Valid @RequestBody ResendCodeRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Solicitud de código procesada",
                identityService.resendVerificationCode(request.email())));
    }

    @PostMapping("/login")
    @Operation(summary = "Iniciar sesión con correo y contraseña (cuenta verificada)")
    public ResponseEntity<ApiResponse<IdentityService.LoginResult>> login(
            @Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Sesión iniciada",
                identityService.login(request.email(), request.password())));
    }

    @PostMapping("/refresh-token")
    @Operation(summary = "Rotar el refresh token y obtener un nuevo access token")
    public ResponseEntity<ApiResponse<IdentityService.TokenPair>> refresh(
            @Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Token renovado", identityService.refresh(request.refreshToken())));
    }

    @PostMapping("/logout")
    @Operation(summary = "Cerrar sesión revocando el refresh token en el servidor")
    public ResponseEntity<ApiResponse<Void>> logout(@Valid @RequestBody RefreshRequest request) {
        identityService.logout(request.refreshToken());
        return ResponseEntity.ok(ApiResponse.ok("Sesión cerrada"));
    }
}
