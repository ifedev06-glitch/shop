package com.example.shopsecure.service;

import com.example.shopsecure.entity.*;
import com.example.shopsecure.repository.OrderCounterpartyRepository;
import com.example.shopsecure.repository.OrderShipmentRepository;
import com.example.shopsecure.repository.OrderTrackingNotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderTrackingNotificationService {

    private static final String TRACKING_READY_SUBJECT = "Track your ShopSecure order";
    private static final String PICKUP_CONFIRMED_SUBJECT = "Carrier pickup confirmed";
    private static final Duration RESEND_IDEMPOTENCY_SAFETY_WINDOW = Duration.ofHours(23);

    private final EmailService emailService;
    private final EmailTemplateService emailTemplateService;
    private final OrderTrackingService trackingService;
    private final OrderCounterpartyRepository counterpartyRepository;
    private final OrderShipmentRepository shipmentRepository;
    private final OrderTrackingNotificationRepository notificationRepository;
    private final PlatformTransactionManager transactionManager;

    @Value("${order.tracking-notification.max-attempts:5}")
    private int maxAttempts;

    @Value("${order.tracking-notification.retry-delay:PT5M}")
    private Duration retryDelay;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(Order order) {
        enqueue(order, OrderTrackingNotificationType.TRACKING_READY);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueCarrierPickup(Order order) {
        enqueue(order, OrderTrackingNotificationType.CARRIER_PICKUP_CONFIRMED);
    }

    private void enqueue(Order order, OrderTrackingNotificationType notificationType) {
        OrderCounterparty counterparty = counterpartyRepository.findByOrder_Id(order.getId()).orElse(null);
        if (counterparty == null) {
            log.warn("Unable to enqueue order notification: orderId={}, type={}, reason=counterparty missing",
                    order.getId(), notificationType);
            return;
        }
        Participant creator = new Participant(
                order.getCreatorRole(),
                order.getCreatorFirstName(),
                order.getCreatorEmail()
        );
        Participant opposite = new Participant(
                order.getCreatorRole().opposite(),
                counterparty.getFirstName(),
                counterparty.getEmail()
        );
        enqueue(order, creator, notificationType);
        enqueue(order, opposite, notificationType);
    }

    public void dispatch(Long notificationId) {
        DispatchClaim claim;
        try {
            claim = inNewTransaction(() -> prepareForDispatch(notificationId));
        } catch (RuntimeException exception) {
            inNewTransaction(() -> {
                completePreparationFailure(notificationId);
                return null;
            });
            log.error("Unable to prepare order tracking email: notificationId={}", notificationId, exception);
            return;
        }
        if (claim == null) {
            return;
        }
        try {
            emailService.sendEmail(
                    claim.recipientEmail(),
                    claim.subject(),
                    claim.htmlBody(),
                    claim.idempotencyKeyPrefix() + "/" + notificationId
            );
            inNewTransaction(() -> {
                completeSuccessfulSend(notificationId, claim.dispatchToken());
                return null;
            });
        } catch (RuntimeException exception) {
            inNewTransaction(() -> {
                completeFailedAttempt(notificationId, claim.dispatchToken());
                return null;
            });
            log.error("Unable to send order tracking email: notificationId={}", notificationId, exception);
        }
    }

    private void enqueue(
            Order order,
            Participant participant,
            OrderTrackingNotificationType notificationType
    ) {
        if (notificationRepository.existsByOrder_IdAndParticipantRoleAndNotificationType(
                order.getId(),
                participant.role(),
                notificationType
        )) {
            return;
        }
        Instant now = Instant.now();
        notificationRepository.save(OrderTrackingNotification.builder()
                .order(order)
                .participantRole(participant.role())
                .notificationType(notificationType)
                .recipientFirstName(participant.firstName())
                .recipientEmail(participant.email())
                .status(OrderTrackingNotificationStatus.PENDING)
                .nextAttemptAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private DispatchClaim prepareForDispatch(Long notificationId) {
        OrderTrackingNotification notification = notificationRepository
                .findByIdForUpdate(notificationId)
                .orElse(null);
        if (notification == null || notification.getStatus() != OrderTrackingNotificationStatus.PENDING) {
            return null;
        }
        Instant now = Instant.now();
        if (notification.getNextAttemptAt() != null && notification.getNextAttemptAt().isAfter(now)) {
            return null;
        }
        if (notification.getFirstAttemptAt() != null
                && !now.isBefore(notification.getFirstAttemptAt().plus(RESEND_IDEMPOTENCY_SAFETY_WINDOW))) {
            markFailed(notification, now,
                    "Prior email outcome exceeds the Resend idempotency window; manual review required");
            return null;
        }
        OrderTrackingService.TrackingLink link = trackingService.linkForOrder(notification.getOrder().getId());
        if (link == null) {
            markSkipped(notification, now, "Order tracking link is no longer active");
            return null;
        }

        UUID dispatchToken = UUID.randomUUID();
        notification.setDispatchToken(dispatchToken);
        notification.setNextAttemptAt(now.plus(validRetryDelay()));
        notification.setFailureReason(null);
        if (notification.getFirstAttemptAt() == null) {
            notification.setFirstAttemptAt(now);
        }
        notification.setUpdatedAt(now);
        notificationRepository.save(notification);
        String subject;
        String idempotencyKeyPrefix;
        String htmlBody;
        if (notification.getNotificationType() == OrderTrackingNotificationType.CARRIER_PICKUP_CONFIRMED) {
            Order order = notification.getOrder();
            if (order.getCarrierPickupConfirmedAt() == null) {
                markSkipped(notification, now, "Carrier pickup has not been confirmed");
                return null;
            }
            OrderShipment shipment = shipmentRepository.findByOrder_Id(order.getId()).orElse(null);
            subject = PICKUP_CONFIRMED_SUBJECT;
            idempotencyKeyPrefix = "order-carrier-pickup";
            htmlBody = emailTemplateService.orderCarrierPickupConfirmedBody(
                    notification.getRecipientFirstName(),
                    notification.getParticipantRole(),
                    order.getItemDescription(),
                    shipment == null ? null : shipment.getTrackingReference(),
                    link.url(),
                    order.getCarrierPickupConfirmedAt(),
                    order.getEscrowReleasePolicy(),
                    order.getSettlementStatus(),
                    shipment == null ? null : shipment.getStatus()
            );
        } else {
            subject = TRACKING_READY_SUBJECT;
            idempotencyKeyPrefix = "order-tracking-ready";
            htmlBody = emailTemplateService.orderTrackingReadyBody(
                    notification.getRecipientFirstName(),
                    notification.getParticipantRole(),
                    link.url()
            );
        }
        return new DispatchClaim(
                dispatchToken,
                notification.getRecipientEmail(),
                subject,
                htmlBody,
                idempotencyKeyPrefix
        );
    }

    private void completeSuccessfulSend(Long notificationId, UUID dispatchToken) {
        OrderTrackingNotification notification = notificationRepository
                .findByIdForUpdate(notificationId)
                .orElse(null);
        if (!isCurrentClaim(notification, dispatchToken)) {
            return;
        }
        Instant now = Instant.now();
        notification.setStatus(OrderTrackingNotificationStatus.SENT);
        notification.setSentAt(now);
        notification.setNextAttemptAt(null);
        notification.setDispatchToken(null);
        notification.setFailureReason(null);
        notification.setUpdatedAt(now);
        notificationRepository.save(notification);
    }

    private void completeFailedAttempt(Long notificationId, UUID dispatchToken) {
        OrderTrackingNotification notification = notificationRepository
                .findByIdForUpdate(notificationId)
                .orElse(null);
        if (!isCurrentClaim(notification, dispatchToken)) {
            return;
        }
        recordFailedAttempt(notification, Instant.now(), "Email delivery failed");
    }

    private void completePreparationFailure(Long notificationId) {
        OrderTrackingNotification notification = notificationRepository
                .findByIdForUpdate(notificationId)
                .orElse(null);
        if (notification == null || notification.getStatus() != OrderTrackingNotificationStatus.PENDING) {
            return;
        }
        recordFailedAttempt(notification, Instant.now(), "Email preparation failed");
    }

    private void recordFailedAttempt(
            OrderTrackingNotification notification,
            Instant now,
            String reason
    ) {
        int attempts = notification.getAttemptCount() + 1;
        notification.setAttemptCount(attempts);
        notification.setDispatchToken(null);
        notification.setFailureReason(reason);
        if (notification.getFirstAttemptAt() == null) {
            notification.setFirstAttemptAt(now);
        }
        if (attempts >= Math.max(1, maxAttempts)) {
            notification.setStatus(OrderTrackingNotificationStatus.FAILED);
            notification.setNextAttemptAt(null);
        } else {
            notification.setNextAttemptAt(now.plus(validRetryDelay().multipliedBy(attempts)));
        }
        notification.setUpdatedAt(now);
        notificationRepository.save(notification);
    }

    private void markFailed(OrderTrackingNotification notification, Instant now, String reason) {
        notification.setStatus(OrderTrackingNotificationStatus.FAILED);
        notification.setNextAttemptAt(null);
        notification.setDispatchToken(null);
        notification.setFailureReason(reason);
        notification.setUpdatedAt(now);
        notificationRepository.save(notification);
    }

    private void markSkipped(OrderTrackingNotification notification, Instant now, String reason) {
        notification.setStatus(OrderTrackingNotificationStatus.SKIPPED);
        notification.setNextAttemptAt(null);
        notification.setDispatchToken(null);
        notification.setFailureReason(reason);
        notification.setUpdatedAt(now);
        notificationRepository.save(notification);
    }

    private boolean isCurrentClaim(OrderTrackingNotification notification, UUID dispatchToken) {
        return notification != null
                && notification.getStatus() == OrderTrackingNotificationStatus.PENDING
                && Objects.equals(notification.getDispatchToken(), dispatchToken);
    }

    private Duration validRetryDelay() {
        return retryDelay == null || retryDelay.isZero() || retryDelay.isNegative()
                ? Duration.ofMinutes(5)
                : retryDelay;
    }

    private <T> T inNewTransaction(Supplier<T> work) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> work.get());
    }

    private record Participant(CreatorRole role, String firstName, String email) {
    }

    private record DispatchClaim(
            UUID dispatchToken,
            String recipientEmail,
            String subject,
            String htmlBody,
            String idempotencyKeyPrefix
    ) {
    }
}
