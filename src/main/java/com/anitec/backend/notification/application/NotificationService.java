package com.anitec.backend.notification.application;

import com.anitec.backend.care.domain.CareRecord;
import com.anitec.backend.care.domain.VeterinaryAppointment;
import com.anitec.backend.linking.domain.LinkingInvitation;
import com.anitec.backend.notification.domain.Notification;
import com.anitec.backend.notification.domain.NotificationRepository;
import com.anitec.backend.notification.domain.PushNotificationPort;
import com.anitec.backend.subscriptions.domain.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Notification service: persists in-app notifications for the domain events
 * of Care, Linking and Subscriptions, and attempts a push delivery through
 * the port. Provider failures are logged and never affect the record.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notifications;
    private final PushNotificationPort pushPort;

    public NotificationService(NotificationRepository notifications, PushNotificationPort pushPort) {
        this.notifications = notifications;
        this.pushPort = pushPort;
    }

    public record NotificationView(UUID id, String type, String title, String message,
                                   boolean read, Instant createdAt) {
        static NotificationView from(Notification notification) {
            return new NotificationView(notification.getId(), notification.getType().name(),
                    notification.getTitle(), notification.getMessage(), notification.isRead(),
                    notification.getCreatedAt());
        }
    }

    // ------------------------------------------------------------- commands

    @Transactional
    public void markRead(UUID userId, UUID notificationId) {
        Notification notification = notifications.findById(notificationId)
                .orElseThrow(() -> com.anitec.backend.shared.domain.DomainException.notFound(
                        "Notificación no encontrada"));
        notification.markRead(userId);
        notifications.save(notification);
    }

    // -------------------------------------------------------------- queries

    @Transactional(readOnly = true)
    public List<NotificationView> listFor(UUID userId, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        return notifications.findByUserIdOrderByReadAscCreatedAtDesc(userId, safePage * safeSize, safeSize)
                .stream().map(NotificationView::from).toList();
    }

    @Transactional(readOnly = true)
    public long countFor(UUID userId) {
        return notifications.countByUserId(userId);
    }

    // ------------------------------------------------------ event listeners

    @EventListener
    @Transactional
    public void onInstructionsRegistered(CareRecord.InstructionsRegistered event) {
        notifyUser(event.ownerId(), Notification.Type.CARE_INSTRUCTIONS_REGISTERED,
                "Nuevas indicaciones de cuidado",
                "El veterinario registró indicaciones de cuidado para uno de sus animales. "
                        + "Inicie sesión para consultarlas.");
    }

    @EventListener
    @Transactional
    public void onInstructionsUpdated(CareRecord.InstructionsUpdated event) {
        notifyUser(event.ownerId(), Notification.Type.CARE_INSTRUCTIONS_UPDATED,
                "Indicaciones de cuidado actualizadas",
                "El veterinario actualizó las indicaciones de cuidado de una atención. "
                        + "Inicie sesión para consultarlas.");
    }

    @EventListener
    @Transactional
    public void onVisitScheduled(VeterinaryAppointment.VisitScheduled event) {
        notifyUser(event.ownerId(), Notification.Type.VISIT_SCHEDULED,
                "Visita veterinaria programada",
                "Se programó una " + event.type().name().toLowerCase()
                        + " para uno de sus animales. Consulte su agenda en la aplicación.");
    }

    @EventListener
    @Transactional
    public void onInvitationSent(LinkingInvitation.InvitationSent event) {
        notifyUser(event.vetId(), Notification.Type.INVITATION_RECEIVED,
                "Invitación de vinculación recibida",
                "Un ganadero le ha invitado a vincularse. Revise la invitación pendiente.");
    }

    @EventListener
    @Transactional
    public void onInvitationAnswered(LinkingInvitation.InvitationAnswered event) {
        String detalle = event.accepted() ? "aceptó" : "rechazó";
        notifyUser(event.farmerId(), Notification.Type.INVITATION_ANSWERED,
                "Invitación " + (event.accepted() ? "aceptada" : "rechazada"),
                "El veterinario " + detalle + " la invitación de vinculación.");
    }

    @EventListener
    @Transactional
    public void onPremiumActivated(Subscription.PremiumActivated event) {
        notifyUser(event.accountId(), Notification.Type.PREMIUM_ACTIVATED,
                "Suscripción premium activa",
                "Su plan premium está activo. Límite vigente: " + event.limit() + ".");
    }

    @EventListener
    @Transactional
    public void onPremiumExpired(Subscription.PremiumExpired event) {
        notifyUser(event.accountId(), Notification.Type.PREMIUM_EXPIRED,
                "Suscripción premium vencida",
                "Su período pagado finalizó y se aplicó el plan gratuito con límite de "
                        + event.limit() + ". Los registros existentes se conservan.");
    }

    // -------------------------------------------------------------- helpers

    private void notifyUser(UUID userId, Notification.Type type, String title, String message) {
        Notification notification = Notification.create(userId, type, title, message, Instant.now());
        notifications.save(notification);
        try {
            // No clinical content in the push payload (report rule).
            pushPort.send(userId, title, message);
        } catch (Exception ex) {
            log.warn("Push delivery failed for user {}: {}", userId, ex.getMessage());
        }
    }
}
