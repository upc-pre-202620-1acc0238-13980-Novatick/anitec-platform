package com.anitec.backend.subscriptions.interfaceapi;

import com.anitec.backend.shared.infrastructure.security.CurrentUser;
import com.anitec.backend.shared.interfaceapi.ApiResponse;
import com.anitec.backend.subscriptions.application.SubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Subscription endpoints (spec #9-#13, US22-US24). */
@RestController
@RequestMapping("/api/v1/subscription")
@Tag(name = "Suscripciones", description = "Planes, pagos y estado de suscripción")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    public record PremiumRequest(@NotNull(message = "obligatorio") UUID planId) {
    }

    public record ConfirmRequest(
            @NotBlank(message = "obligatorio") String checkoutId,
            @NotNull(message = "obligatorio") SubscriptionService.PaymentOutcome outcome) {
    }

    @GetMapping("/plans")
    @Operation(summary = "Planes compatibles con el perfil del usuario (US22)")
    public ResponseEntity<ApiResponse<List<SubscriptionService.PlanView>>> plans() {
        return ResponseEntity.ok(ApiResponse.ok(
                subscriptionService.plansFor(CurrentUser.role())));
    }

    @GetMapping("/my-subscription")
    @Operation(summary = "Suscripción vigente: plan, límite, estado y renovación (US22)")
    public ResponseEntity<ApiResponse<SubscriptionService.SubscriptionView>> mySubscription() {
        return ResponseEntity.ok(ApiResponse.ok(
                subscriptionService.mySubscription(CurrentUser.id())));
    }

    @PostMapping("/premium")
    @Operation(summary = "Contratar premium: crea el checkout (valida el perfil antes de cobrar, US23)")
    public ResponseEntity<ApiResponse<SubscriptionService.CheckoutResult>> requestPremium(
            @Valid @RequestBody PremiumRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Checkout creado",
                subscriptionService.requestPremium(CurrentUser.id(), request.planId())));
    }

    @PostMapping("/premium/confirm")
    @Operation(summary = "Confirmar el resultado del pago (idempotente, TS02)")
    public ResponseEntity<ApiResponse<SubscriptionService.PaymentResultView>> confirm(
            @Valid @RequestBody ConfirmRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Resultado procesado",
                subscriptionService.confirmPayment(CurrentUser.id(), request.checkoutId(),
                        request.outcome())));
    }

    @PostMapping("/cancel-renewal")
    @Operation(summary = "Cancelar la renovación manteniendo premium hasta fin de período (US24)")
    public ResponseEntity<ApiResponse<SubscriptionService.SubscriptionView>> cancelRenewal() {
        SubscriptionService.SubscriptionView view =
                subscriptionService.cancelRenewal(CurrentUser.id());
        return ResponseEntity.ok(ApiResponse.ok(
                "Renovación cancelada; conserva los beneficios hasta el fin del período pagado", view));
    }
}
