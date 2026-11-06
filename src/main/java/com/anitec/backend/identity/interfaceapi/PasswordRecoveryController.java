package com.anitec.backend.identity.interfaceapi;

import com.anitec.backend.identity.application.PasswordRecoveryService;
import com.anitec.backend.identity.domain.EmailLanguage;
import com.anitec.backend.identity.domain.VerificationThrottle;
import com.anitec.backend.shared.interfaceapi.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Clock;
import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth/password-recovery")
@Tag(name="Auth")
public class PasswordRecoveryController {
    private final PasswordRecoveryService service;
    private final PasswordRecoveryOriginLimiter limiter;
    private final Clock clock;
    public PasswordRecoveryController(PasswordRecoveryService service,PasswordRecoveryOriginLimiter limiter,Clock clock) {
        this.service=service;this.limiter=limiter;this.clock=clock;
    }
    @io.swagger.v3.oas.annotations.media.Schema(name = "PasswordRecoveryRequest")
    public record Request(@NotBlank @Email @Size(max=150) String email) {
        public Request { email=com.anitec.backend.identity.domain.Account.normalizeEmail(email); }
    }
    @io.swagger.v3.oas.annotations.media.Schema(name = "PasswordRecoveryConfirm")
    public record Confirm(@NotBlank @Email @Size(max=150) String email,String code,String newPassword) {
        public Confirm { email=com.anitec.backend.identity.domain.Account.normalizeEmail(email); }
        @Override public String toString(){return "PasswordRecoveryConfirm[REDACTED]";}
    }
    @PostMapping("/request")
    @Operation(summary="Solicitar recuperación de contraseña",description="202 genérico: aceptación para procesamiento, no entrega. Deshabilitado por defecto.")
    public ResponseEntity<?> request(@Valid @RequestBody Request body,HttpServletRequest http,
                                    @RequestHeader(value="Accept-Language",required=false) String language) {
        service.requireAvailable();
        try { limiter.check(http.getRemoteAddr(),"request"); }
        catch (VerificationThrottle throttle) { return limited(throttle); }
        service.request(body.email(),EmailLanguage.fromAcceptLanguage(language));
        return ResponseEntity.accepted().body(ApiResponse.ok("Si la cuenta puede recuperarse, recibirás un código en el correo indicado"));
    }
    @PostMapping("/confirm")
    @Operation(summary="Restablecer contraseña con código de seis dígitos",description="204 sin tokens; requiere nuevo login. Código inválido: 400 RECOVERY_CODE_INVALID.")
    public ResponseEntity<?> confirm(@Valid @RequestBody Confirm body,HttpServletRequest http) {
        service.requireAvailable();
        try { limiter.check(http.getRemoteAddr(),"confirm"); }
        catch (VerificationThrottle throttle) { return limited(throttle); }
        service.confirm(body.email(),body.code(),body.newPassword());
        return ResponseEntity.noContent().build();
    }
    private ResponseEntity<?> limited(VerificationThrottle throttle) {
        long seconds=Math.max(1,(Duration.between(clock.instant(),throttle.retryAt()).toMillis()+999)/1000);
        return ResponseEntity.status(429).header("Retry-After",Long.toString(seconds))
                .body(ApiResponse.error("Demasiadas solicitudes desde este origen","RECOVERY_ORIGIN_LIMIT",null));
    }
}
