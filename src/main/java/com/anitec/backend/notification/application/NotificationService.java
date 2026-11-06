package com.anitec.backend.notification.application;

import com.anitec.backend.care.domain.CareRecord;
import com.anitec.backend.care.domain.VeterinaryAppointment;
import com.anitec.backend.linking.domain.LinkingInvitation;
import com.anitec.backend.identity.application.IdentityQueries;
import com.anitec.backend.livestock.application.LivestockQueries;
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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
    private final LivestockQueries livestockQueries;
    private final IdentityQueries identityQueries;
    private static final ZoneId VISIT_ZONE = ZoneId.of("America/Lima");
    private static final DateTimeFormatter VISIT_TIME = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm")
            .withZone(VISIT_ZONE);

    public NotificationService(NotificationRepository notifications, PushNotificationPort pushPort,
                               LivestockQueries livestockQueries, IdentityQueries identityQueries) {
        this.notifications = notifications;
        this.pushPort = pushPort;
        this.livestockQueries = livestockQueries;
        this.identityQueries = identityQueries;
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
        var animal = livestockQueries.requireAnimalInScope(event.animalId(), List.of(event.ownerId()));
        var farm = livestockQueries.requireFarmInScope(animal.getFarmId(), List.of(event.ownerId()));
        String animalLabel = animal.getName() == null || animal.getName().isBlank()
                ? animal.codeValue() : animal.getName() + " (" + animal.codeValue() + ")";
        String veterinarian = identityQueries.displayNames(List.of(event.veterinarianId()))
                .getOrDefault(event.veterinarianId(), "Veterinario");
        String location = farm.getLocation() == null || farm.getLocation().isBlank()
                ? "Ubicación no registrada" : farm.getLocation();
        boolean control = event.type() == VeterinaryAppointment.Type.CONTROL;
        notifyUser(event.ownerId(), Notification.Type.VISIT_SCHEDULED,
                control ? "Control veterinario programado" : "Visita veterinaria programada",
                "Se programó " + (control ? "un control" : "una visita") + " para " + animalLabel
                        + " con " + veterinarian + " el " + VISIT_TIME.format(event.scheduledAt())
                        + " (" + VISIT_ZONE.getId() + "). Finca: " + farm.getName()
                        + ". Ubicación: " + location + ".",
                // Keep animal/farm/location snapshots out of the console/future push channel.
                "Hay un nuevo aviso de visita o control en su bandeja de notificaciones.");
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
        notifyUser(userId, type, title, message, message);
    }

    private void notifyUser(UUID userId, Notification.Type type, String title, String message, String pushMessage) {
        Notification notification = Notification.create(userId, type, title, message, Instant.now());
        notifications.save(notification);
        try {
            // No clinical content in the push payload (report rule).
            pushPort.send(userId, title, pushMessage);
        } catch (Exception ex) {
            log.warn("Push delivery failed for user {}: {}", userId, ex.getMessage());
        }
    }
}
