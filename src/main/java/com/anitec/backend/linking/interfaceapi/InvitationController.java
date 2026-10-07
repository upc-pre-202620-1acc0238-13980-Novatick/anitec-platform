package com.anitec.backend.linking.interfaceapi;

import com.anitec.backend.identity.application.IdentityQueries;
import com.anitec.backend.linking.application.LinkingQueryService;
import com.anitec.backend.linking.application.LinkingService;
import com.anitec.backend.linking.domain.LinkingInvitation;
import com.anitec.backend.shared.domain.DomainException;
import com.anitec.backend.shared.domain.Role;
import com.anitec.backend.shared.infrastructure.security.CurrentUser;
import com.anitec.backend.shared.interfaceapi.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

import java.util.List;

/** Invitation endpoints (spec #32-#34, US14-US16). */
@RestController
@RequestMapping("/api/v1/invitations")
@Tag(name = "Invitaciones", description = "Invitaciones de vinculación veterinaria")
public class InvitationController {

    private final LinkingService linkingService;
    private final LinkingQueryService linkingQueries;

    public InvitationController(LinkingService linkingService, LinkingQueryService linkingQueries) {
        this.linkingService = linkingService;
        this.linkingQueries = linkingQueries;
    }

    public record SendInvitationRequest(@NotBlank(message = "obligatorio")
                                        @Size(max = 150, message = "máximo 150 caracteres")
                                        @Email(message = "correo inválido") String vetEmail) {
    }

    public record RespondRequest(@NotNull(message = "obligatorio") LinkingService.RespondAction action) {
    }

    public record InvitationResponse(java.util.UUID invitationId, String vetEmail, String status,
                                     String emailDelivery, java.time.Instant sentAt) {
        static InvitationResponse from(LinkingInvitation invitation) {
            return new InvitationResponse(invitation.getId(), invitation.getVetEmail(),
                    invitation.getStatus().name(), invitation.getEmailDelivery(), invitation.getSentAt());
        }
    }

    @PostMapping
    @Operation(summary = "Enviar invitación a un veterinario registrado (Ganadero)")
    public ResponseEntity<ApiResponse<InvitationResponse>> send(@Valid @RequestBody SendInvitationRequest request) {
        LinkingInvitation invitation = linkingService.sendInvitation(CurrentUser.id(), request.vetEmail());
        String message = "UNCONFIRMED".equals(invitation.getEmailDelivery())
                ? "Invitación registrada; el envío del correo no pudo confirmarse"
                : "Invitación registrada y enviada por correo";
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(message, InvitationResponse.from(invitation)));
    }

    @GetMapping("/pending")
    @Operation(summary = "Invitaciones pendientes del veterinario autenticado (US16)")
    public ResponseEntity<ApiResponse<List<LinkingQueryService.PendingInvitationView>>> pending() {
        requireVeterinarian();
        return ResponseEntity.ok(ApiResponse.ok(linkingQueries.pendingInvitations(CurrentUser.id())));
    }

    @PutMapping("/{invitationId}/respond")
    @Operation(summary = "Aceptar o rechazar una invitación (solo el destinatario, US15)")
    public ResponseEntity<ApiResponse<InvitationResponse>> respond(
            @PathVariable java.util.UUID invitationId, @Valid @RequestBody RespondRequest request) {
        requireVeterinarian();
        LinkingInvitation invitation = linkingService.respond(
                CurrentUser.id(), invitationId, request.action());
        return ResponseEntity.ok(ApiResponse.ok(
                request.action() == LinkingService.RespondAction.ACCEPT
                        ? "Invitación aceptada; vinculación activa"
                        : "Invitación rechazada",
                InvitationResponse.from(invitation)));
    }

    private void requireVeterinarian() {
        if (CurrentUser.role() != Role.VETERINARIO) {
            throw DomainException.forbidden("Esta operación es exclusiva del perfil veterinario");
        }
    }
}
