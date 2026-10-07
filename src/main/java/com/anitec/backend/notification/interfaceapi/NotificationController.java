package com.anitec.backend.notification.interfaceapi;

import com.anitec.backend.notification.application.NotificationService;
import com.anitec.backend.shared.infrastructure.security.CurrentUser;
import com.anitec.backend.shared.interfaceapi.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Notification endpoints (spec #50-#51). */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notificaciones", description = "Notificaciones in-app del usuario autenticado")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    @Operation(summary = "Listar mis notificaciones (no leídas primero)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID me = CurrentUser.id();
        List<NotificationService.NotificationView> content = notificationService.listFor(me, page, size);
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "content", content,
                "page", Math.max(page, 0),
                "size", Math.min(Math.max(size, 1), 100),
                "total", notificationService.countFor(me))));
    }

    @PatchMapping("/{notificationId}/read")
    @Operation(summary = "Marcar una notificación como leída")
    public ResponseEntity<ApiResponse<Void>> markRead(@PathVariable UUID notificationId) {
        notificationService.markRead(CurrentUser.id(), notificationId);
        return ResponseEntity.ok(ApiResponse.ok("Notificación marcada como leída"));
    }
}
