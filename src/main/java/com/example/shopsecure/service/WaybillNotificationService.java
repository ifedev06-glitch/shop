package com.example.shopsecure.service;

import com.example.shopsecure.entity.Waybill;
import com.example.shopsecure.entity.WaybillEmailNotification;
import com.example.shopsecure.entity.WaybillEmailNotificationStatus;
import com.example.shopsecure.entity.WaybillEmailNotificationType;
import com.example.shopsecure.entity.WaybillParty;
import com.example.shopsecure.entity.WaybillPartyRole;
import com.example.shopsecure.entity.WaybillShipment;
import com.example.shopsecure.entity.WaybillStatus;
import com.example.shopsecure.provider.shipping.ShipmentStatus;
import com.example.shopsecure.provider.shipping.ShipmentTrackingStatus;
import com.example.shopsecure.repository.WaybillEmailNotificationRepository;
import com.example.shopsecure.repository.WaybillPartyRepository;
import com.example.shopsecure.repository.WaybillShipmentRepository;
import com.example.shopsecure.util.TrackingUrl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
@Slf4j
public class WaybillNotificationService {

    private static final String AWAITING_PICKUP_SUBJECT =
            "Payment confirmed - package entered pickup stage";
    private static final Duration RESEND_IDEMPOTENCY_SAFETY_WINDOW = Duration.ofHours(23);
    private static final Set<WaybillStatus> TERMINAL_NOTIFICATION_STATUSES = EnumSet.of(
            WaybillStatus.CANCELLATION_PENDING,
            WaybillStatus.REFUND_PENDING,
            WaybillStatus.COMPLETED,
            WaybillStatus.CANCELLED,
            WaybillStatus.REFUNDED
    );
    private static final Set<ShipmentStatus> EMAIL_READY_SHIPMENT_STATUSES = EnumSet.of(
            ShipmentStatus.CONFIRMED,
            ShipmentStatus.IN_TRANSIT
    );

    private final EmailService emailService;
    private final EmailTemplateService emailTemplateService;
    private final WaybillPartyRepository partyRepository;
    private final WaybillShipmentRepository shipmentRepository;
    private final WaybillEmailNotificationRepository notificationRepository;
    private final ShippingProviderRoutingService shippingRoutingService;
    private final PlatformTransactionManager transactionManager;

    @Value("${waybill.notification.max-attempts:5}")
    private int maxAttempts;

    @Value("${waybill.notification.retry-delay:PT5M}")
    private Duration retryDelay;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueAwaitingPickup(Waybill waybill, WaybillShipment shipment) {
        List<WaybillParty> parties = partyRepository.findByWaybill_IdOrderByRoleAsc(waybill.getId());
        Set<WaybillPartyRole> roles = EnumSet.noneOf(WaybillPartyRole.class);
        parties.forEach(party -> roles.add(party.getRole()));
        if (!roles.equals(EnumSet.allOf(WaybillPartyRole.class)) || parties.size() != 2) {
            throw new IllegalStateException("A waybill requires one sender and one recipient notification");
        }

        LocalDateTime now = utcNow();
        for (WaybillParty party : parties) {
            if (notificationRepository.existsByWaybill_IdAndParty_IdAndNotificationType(
                    waybill.getId(),
                    party.getId(),
                    WaybillEmailNotificationType.AWAITING_PICKUP
            )) {
                continue;
            }
            notificationRepository.save(WaybillEmailNotification.builder()
                    .waybill(waybill)
                    .party(party)
                    .notificationType(WaybillEmailNotificationType.AWAITING_PICKUP)
                    .status(WaybillEmailNotificationStatus.PENDING)
                    .recipientEmail(party.getEmail())
                    .subject(AWAITING_PICKUP_SUBJECT)
                    .htmlBody(body(waybill, shipment, party, now))
                    .nextAttemptAt(now)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }
    }

    public void dispatch(Long notificationId) {
        DispatchPreparation preparation = inNewTransaction(
                () -> prepareForDispatch(notificationId)
        );
        if (preparation == null) {
            return;
        }

        DispatchClaim claim = preparation.trackingRefreshRequired()
                ? refreshTrackingAndPrepare(notificationId, preparation)
                : preparation.toClaim();
        if (claim == null) {
            return;
        }

        try {
            emailService.sendEmail(
                    claim.recipientEmail(),
                    claim.subject(),
                    claim.htmlBody(),
                    idempotencyKey(notificationId)
            );
            inNewTransaction(() -> {
                completeSuccessfulSend(notificationId, claim.dispatchToken());
                return null;
            });
        } catch (RuntimeException exception) {
            inNewTransaction(() -> {
                completeFailedAttempt(
                        notificationId,
                        claim.dispatchToken(),
                        "Email delivery failed"
                );
                return null;
            });
            log.error("Unable to send waybill awaiting-pickup email: notificationId={}",
                    notificationId, exception);
        }
    }

    private DispatchPreparation prepareForDispatch(Long notificationId) {
        WaybillEmailNotification notification = notificationRepository
                .findByIdForUpdate(notificationId)
                .orElse(null);
        if (notification == null || notification.getStatus() != WaybillEmailNotificationStatus.PENDING) {
            return null;
        }
        LocalDateTime now = utcNow();
        if (notification.getNextAttemptAt() != null
                && notification.getNextAttemptAt().isAfter(now)) {
            return null;
        }
        if (!awaitPickupStage(notification, now)) {
            return null;
        }
        if (notification.getFirstAttemptAt() != null
                && !now.isBefore(notification.getFirstAttemptAt().plus(
                RESEND_IDEMPOTENCY_SAFETY_WINDOW
        ))) {
            markFailed(
                    notification,
                    now,
                    "Prior email outcome exceeds the Resend idempotency window; manual review required"
            );
            return null;
        }

        WaybillShipment shipment = shipmentRepository
                .findByWaybill_Id(notification.getWaybill().getId())
                .orElse(null);
        boolean shipmentReady = shipment != null
                && EMAIL_READY_SHIPMENT_STATUSES.contains(shipment.getStatus());
        if (notification.getHtmlBody() == null && shipmentReady) {
            String body = shipment == null
                    ? null
                    : body(
                            notification.getWaybill(),
                            shipment,
                            notification.getParty(),
                            notification.getCreatedAt()
                    );
            notification.setHtmlBody(body);
        }
        boolean trackingRefreshRequired = !shipmentReady || notification.getHtmlBody() == null;

        UUID dispatchToken = UUID.randomUUID();
        notification.setDispatchToken(dispatchToken);
        notification.setNextAttemptAt(now.plus(validRetryDelay()));
        notification.setUpdatedAt(now);
        if (notification.getHtmlBody() != null) {
            notification.setFailureReason(null);
        }
        if (!trackingRefreshRequired && notification.getFirstAttemptAt() == null) {
            notification.setFirstAttemptAt(now);
        }
        notificationRepository.save(notification);
        return new DispatchPreparation(
                dispatchToken,
                notification.getRecipientEmail(),
                notification.getSubject(),
                notification.getHtmlBody(),
                shipment == null ? null : shipment.getProvider(),
                shipment == null ? null : shipment.getProviderShipmentId(),
                trackingRefreshRequired
        );
    }

    private DispatchClaim refreshTrackingAndPrepare(
            Long notificationId,
            DispatchPreparation preparation
    ) {
        if (!hasText(preparation.providerShipmentId())) {
            recordRefreshFailure(
                    notificationId,
                    preparation.dispatchToken(),
                    "Waybill shipment is not available for tracking"
            );
            return null;
        }

        ShipmentTrackingStatus tracking;
        try {
            tracking = shippingRoutingService.require(preparation.providerCode())
                    .trackShipment(preparation.providerShipmentId());
        } catch (RuntimeException exception) {
            recordRefreshFailure(
                    notificationId,
                    preparation.dispatchToken(),
                    "Unable to obtain the carrier tracking link"
            );
            log.error("Unable to refresh tracking for waybill email: notificationId={}",
                    notificationId, exception);
            return null;
        }

        return inNewTransaction(() -> applyTrackingRefresh(
                notificationId,
                preparation,
                tracking
        ));
    }

    private DispatchClaim applyTrackingRefresh(
            Long notificationId,
            DispatchPreparation preparation,
            ShipmentTrackingStatus tracking
    ) {
        WaybillEmailNotification notification = notificationRepository
                .findByIdForUpdate(notificationId)
                .orElse(null);
        if (!isCurrentClaim(notification, preparation.dispatchToken())) {
            return null;
        }
        LocalDateTime now = utcNow();
        if (!awaitPickupStage(notification, now)) {
            return null;
        }
        if (tracking == null
                || tracking.currentStatus() == null
                || !preparation.providerShipmentId().equals(tracking.shipmentId())) {
            deferClaim(notification, now, "Carrier returned mismatched tracking data");
            return null;
        }
        if (tracking.currentStatus() == ShipmentStatus.DELIVERED
                || tracking.currentStatus() == ShipmentStatus.CANCELLED) {
            markSkipped(notification, now, "Carrier shipment is no longer awaiting pickup");
            return null;
        }
        if (tracking.currentStatus() == ShipmentStatus.FAILED) {
            deferClaim(notification, now, "Carrier shipment failed and may still recover");
            return null;
        }
        if (!EMAIL_READY_SHIPMENT_STATUSES.contains(tracking.currentStatus())) {
            deferClaim(notification, now, "Carrier booking is not confirmed yet");
            return null;
        }

        WaybillShipment shipment = shipmentRepository
                .findByWaybillIdForUpdate(notification.getWaybill().getId())
                .orElse(null);
        if (shipment == null
                || !preparation.providerShipmentId().equals(shipment.getProviderShipmentId())) {
            deferClaim(notification, now, "Waybill shipment changed during tracking refresh");
            return null;
        }
        String trackingUrl = TrackingUrl.normalizeHttps(tracking.trackingUrl());
        if (trackingUrl == null) {
            deferClaim(notification, now, "Tracking URL is not available yet");
            return null;
        }
        boolean trackingStateAccepted = applyNotificationTrackingState(shipment, tracking);
        if (!trackingStateAccepted
                && !EMAIL_READY_SHIPMENT_STATUSES.contains(shipment.getStatus())) {
            deferClaim(notification, now, "Carrier tracking response is older than stored state");
            return null;
        }
        if (trackingStateAccepted || !hasText(shipment.getTrackingReference())) {
            if (hasText(tracking.trackingReference())) {
                shipment.setTrackingReference(tracking.trackingReference());
            }
        }
        if (trackingStateAccepted || !hasText(shipment.getTrackingUrl())) {
            shipment.setTrackingUrl(trackingUrl);
        }
        shipment.setUpdatedAt(now);
        shipmentRepository.save(shipment);

        String htmlBody = body(
                notification.getWaybill(),
                shipment,
                notification.getParty(),
                notification.getCreatedAt()
        );
        notification.setHtmlBody(htmlBody);
        notification.setFailureReason(null);
        if (notification.getFirstAttemptAt() == null) {
            notification.setFirstAttemptAt(now);
        }
        notification.setUpdatedAt(now);
        notificationRepository.save(notification);
        return new DispatchClaim(
                preparation.dispatchToken(),
                notification.getRecipientEmail(),
                notification.getSubject(),
                htmlBody
        );
    }

    private void recordRefreshFailure(Long notificationId, UUID dispatchToken, String reason) {
        inNewTransaction(() -> {
            WaybillEmailNotification notification = notificationRepository
                    .findByIdForUpdate(notificationId)
                    .orElse(null);
            if (isCurrentClaim(notification, dispatchToken)) {
                deferClaim(notification, utcNow(), reason);
            }
            return null;
        });
    }

    private void completeSuccessfulSend(Long notificationId, UUID dispatchToken) {
        WaybillEmailNotification notification = notificationRepository
                .findByIdForUpdate(notificationId)
                .orElse(null);
        if (!isCurrentClaim(notification, dispatchToken)) {
            return;
        }
        LocalDateTime now = utcNow();
        notification.setStatus(WaybillEmailNotificationStatus.SENT);
        notification.setSentAt(now);
        notification.setNextAttemptAt(null);
        notification.setDispatchToken(null);
        notification.setFailureReason(null);
        notification.setUpdatedAt(now);
        notificationRepository.save(notification);
    }

    private void completeFailedAttempt(Long notificationId, UUID dispatchToken, String reason) {
        WaybillEmailNotification notification = notificationRepository
                .findByIdForUpdate(notificationId)
                .orElse(null);
        if (isCurrentClaim(notification, dispatchToken)) {
            recordFailedAttempt(notification, utcNow(), reason);
        }
    }

    private void recordFailedAttempt(
            WaybillEmailNotification notification,
            LocalDateTime now,
            String reason
    ) {
        int attempts = notification.getAttemptCount() + 1;
        notification.setAttemptCount(attempts);
        notification.setFailureReason(reason);
        notification.setDispatchToken(null);
        if (attempts >= Math.max(1, maxAttempts)) {
            notification.setStatus(WaybillEmailNotificationStatus.FAILED);
            notification.setNextAttemptAt(null);
        } else {
            notification.setNextAttemptAt(now.plus(validRetryDelay().multipliedBy(attempts)));
        }
        notification.setUpdatedAt(now);
        notificationRepository.save(notification);
    }

    private boolean awaitPickupStage(
            WaybillEmailNotification notification,
            LocalDateTime now
    ) {
        WaybillStatus status = notification.getWaybill().getStatus();
        if (status == WaybillStatus.READY_FOR_DELIVERY) {
            return true;
        }
        if (TERMINAL_NOTIFICATION_STATUSES.contains(status)) {
            markSkipped(notification, now, "Waybill is no longer in the carrier pickup stage");
        } else {
            deferClaim(notification, now, "Waiting for the waybill to return to the pickup stage");
        }
        return false;
    }

    private void deferClaim(
            WaybillEmailNotification notification,
            LocalDateTime now,
            String reason
    ) {
        notification.setDispatchToken(null);
        notification.setNextAttemptAt(now.plus(validRetryDelay()));
        notification.setFailureReason(reason);
        notification.setUpdatedAt(now);
        notificationRepository.save(notification);
    }

    private void markFailed(
            WaybillEmailNotification notification,
            LocalDateTime now,
            String reason
    ) {
        notification.setStatus(WaybillEmailNotificationStatus.FAILED);
        notification.setNextAttemptAt(null);
        notification.setDispatchToken(null);
        notification.setFailureReason(reason);
        notification.setUpdatedAt(now);
        notificationRepository.save(notification);
    }

    private void markSkipped(
            WaybillEmailNotification notification,
            LocalDateTime now,
            String reason
    ) {
        notification.setStatus(WaybillEmailNotificationStatus.SKIPPED);
        notification.setNextAttemptAt(null);
        notification.setDispatchToken(null);
        notification.setFailureReason(reason);
        notification.setUpdatedAt(now);
        notificationRepository.save(notification);
    }

    private boolean isCurrentClaim(WaybillEmailNotification notification, UUID dispatchToken) {
        return notification != null
                && notification.getStatus() == WaybillEmailNotificationStatus.PENDING
                && Objects.equals(notification.getDispatchToken(), dispatchToken);
    }

    private boolean applyNotificationTrackingState(
            WaybillShipment shipment,
            ShipmentTrackingStatus tracking
    ) {
        LocalDateTime providerTime = tracking.lastUpdated() == null
                ? null
                : LocalDateTime.ofInstant(tracking.lastUpdated(), ZoneOffset.UTC);
        if (providerTime != null
                && shipment.getProviderUpdatedAt() != null
                && !providerTime.isAfter(shipment.getProviderUpdatedAt())) {
            return false;
        }
        boolean recoverableFailure = shipment.getStatus() == ShipmentStatus.FAILED
                && EMAIL_READY_SHIPMENT_STATUSES.contains(tracking.currentStatus());
        if (!recoverableFailure
                && pickupStatusRank(tracking.currentStatus()) < pickupStatusRank(shipment.getStatus())) {
            return false;
        }
        shipment.setStatus(tracking.currentStatus());
        if (providerTime != null) {
            shipment.setProviderUpdatedAt(providerTime);
        }
        return true;
    }

    private int pickupStatusRank(ShipmentStatus status) {
        return switch (status) {
            case DRAFT, UNKNOWN -> 0;
            case PENDING -> 1;
            case CONFIRMED -> 2;
            case IN_TRANSIT -> 3;
            case DELIVERED, CANCELLED, FAILED -> 4;
        };
    }

    private String body(
            Waybill waybill,
            WaybillShipment shipment,
            WaybillParty party,
            LocalDateTime eventTime
    ) {
        String trackingUrl = TrackingUrl.normalizeHttps(shipment.getTrackingUrl());
        if (trackingUrl == null) {
            return null;
        }
        return emailTemplateService.waybillAwaitingPickupBody(
                party.getFirstName(),
                party.getRole(),
                waybill.getDescription(),
                shipment.getTrackingReference(),
                trackingUrl,
                eventTime
        );
    }

    private String idempotencyKey(Long notificationId) {
        return "waybill-awaiting-pickup/" + notificationId;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private <T> T inNewTransaction(Supplier<T> work) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> work.get());
    }

    private Duration validRetryDelay() {
        return retryDelay == null || retryDelay.isZero() || retryDelay.isNegative()
                ? Duration.ofMinutes(5)
                : retryDelay;
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private record DispatchPreparation(
            UUID dispatchToken,
            String recipientEmail,
            String subject,
            String htmlBody,
            String providerCode,
            String providerShipmentId,
            boolean trackingRefreshRequired
    ) {
        private DispatchClaim toClaim() {
            return new DispatchClaim(dispatchToken, recipientEmail, subject, htmlBody);
        }
    }

    private record DispatchClaim(
            UUID dispatchToken,
            String recipientEmail,
            String subject,
            String htmlBody
    ) {
    }
}
