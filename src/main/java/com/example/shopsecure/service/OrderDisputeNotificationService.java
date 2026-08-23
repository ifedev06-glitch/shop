package com.example.shopsecure.service;

import com.example.shopsecure.entity.*;
import com.example.shopsecure.repository.OrderDisputeNotificationRepository;
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
public class OrderDisputeNotificationService {

    private static final Duration RESEND_IDEMPOTENCY_SAFETY_WINDOW = Duration.ofHours(23);
    private static final String REQUIRED_OPERATIONS_EMAIL = "fizzois580@gmail.com";

    private final EmailService emailService;
    private final EmailTemplateService emailTemplateService;
    private final OrderDisputeNotificationRepository notificationRepository;
    private final PlatformTransactionManager transactionManager;

    @Value("${order.dispute-notification.max-attempts:5}")
    private int maxAttempts;

    @Value("${order.dispute-notification.retry-delay:PT5M}")
    private Duration retryDelay;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueOpened(OrderDispute dispute, OrderCounterparty counterparty) {
        Order order = dispute.getOrder();
        Participant creator = new Participant(
                audience(order.getCreatorRole()),
                order.getCreatorFirstName(),
                order.getCreatorEmail()
        );
        Participant opposite = new Participant(
                audience(order.getCreatorRole().opposite()),
                counterparty.getFirstName(),
                counterparty.getEmail()
        );
        enqueue(dispute, creator);
        enqueue(dispute, opposite);
        enqueue(dispute, new Participant(
                OrderDisputeNotificationAudience.OPERATIONS,
                "Operations",
                REQUIRED_OPERATIONS_EMAIL
        ));
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
            log.error("Unable to prepare order dispute email: notificationId={}", notificationId, exception);
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
                    "order-dispute-opened/" + notificationId
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
            log.error("Unable to send order dispute email: notificationId={}", notificationId, exception);
        }
    }

    private void enqueue(OrderDispute dispute, Participant participant) {
        if (notificationRepository.existsByDispute_IdAndAudience(dispute.getId(), participant.audience())) {
            return;
        }
        Instant now = Instant.now();
        notificationRepository.save(OrderDisputeNotification.builder()
                .dispute(dispute)
                .audience(participant.audience())
                .recipientFirstName(participant.firstName())
                .recipientEmail(participant.email())
                .status(OrderDisputeNotificationStatus.PENDING)
                .nextAttemptAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private DispatchClaim prepareForDispatch(Long notificationId) {
        OrderDisputeNotification notification = notificationRepository
                .findByIdForUpdate(notificationId)
                .orElse(null);
        if (notification == null || notification.getStatus() != OrderDisputeNotificationStatus.PENDING) {
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

        UUID dispatchToken = UUID.randomUUID();
        notification.setDispatchToken(dispatchToken);
        notification.setNextAttemptAt(now.plus(validRetryDelay()));
        notification.setFailureReason(null);
        if (notification.getFirstAttemptAt() == null) {
            notification.setFirstAttemptAt(now);
        }
        notification.setUpdatedAt(now);
        notificationRepository.save(notification);

        OrderDispute dispute = notification.getDispute();
        Order order = dispute.getOrder();
        boolean operations = notification.getAudience() == OrderDisputeNotificationAudience.OPERATIONS;
        String subject = operations
                ? "New ShopSecure order dispute #" + order.getId()
                : "ShopSecure order dispute received #" + order.getId();
        String htmlBody = emailTemplateService.orderDisputeOpenedBody(
                notification.getRecipientFirstName(),
                notification.getAudience(),
                order.getId(),
                order.getItemDescription(),
                dispute.getReason()
        );
        return new DispatchClaim(dispatchToken, notification.getRecipientEmail(), subject, htmlBody);
    }

    private void completeSuccessfulSend(Long notificationId, UUID dispatchToken) {
        OrderDisputeNotification notification = notificationRepository
                .findByIdForUpdate(notificationId)
                .orElse(null);
        if (!isCurrentClaim(notification, dispatchToken)) {
            return;
        }
        Instant now = Instant.now();
        notification.setStatus(OrderDisputeNotificationStatus.SENT);
        notification.setSentAt(now);
        notification.setNextAttemptAt(null);
        notification.setDispatchToken(null);
        notification.setFailureReason(null);
        notification.setUpdatedAt(now);
        notificationRepository.save(notification);
    }

    private void completeFailedAttempt(Long notificationId, UUID dispatchToken) {
        OrderDisputeNotification notification = notificationRepository
                .findByIdForUpdate(notificationId)
                .orElse(null);
        if (!isCurrentClaim(notification, dispatchToken)) {
            return;
        }
        recordFailedAttempt(notification, Instant.now(), "Email delivery failed");
    }

    private void completePreparationFailure(Long notificationId) {
        OrderDisputeNotification notification = notificationRepository
                .findByIdForUpdate(notificationId)
                .orElse(null);
        if (notification == null || notification.getStatus() != OrderDisputeNotificationStatus.PENDING) {
            return;
        }
        Instant now = Instant.now();
        if (notification.getDispatchToken() != null
                || (notification.getNextAttemptAt() != null && notification.getNextAttemptAt().isAfter(now))) {
            return;
        }
        recordFailedAttempt(notification, now, "Email preparation failed");
    }

    private void recordFailedAttempt(
            OrderDisputeNotification notification,
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
            notification.setStatus(OrderDisputeNotificationStatus.FAILED);
            notification.setNextAttemptAt(null);
        } else {
            notification.setNextAttemptAt(now.plus(validRetryDelay().multipliedBy(attempts)));
        }
        notification.setUpdatedAt(now);
        notificationRepository.save(notification);
    }

    private void markFailed(OrderDisputeNotification notification, Instant now, String reason) {
        notification.setStatus(OrderDisputeNotificationStatus.FAILED);
        notification.setNextAttemptAt(null);
        notification.setDispatchToken(null);
        notification.setFailureReason(reason);
        notification.setUpdatedAt(now);
        notificationRepository.save(notification);
    }

    private boolean isCurrentClaim(OrderDisputeNotification notification, UUID dispatchToken) {
        return notification != null
                && notification.getStatus() == OrderDisputeNotificationStatus.PENDING
                && Objects.equals(notification.getDispatchToken(), dispatchToken);
    }

    private Duration validRetryDelay() {
        return retryDelay == null || retryDelay.isZero() || retryDelay.isNegative()
                ? Duration.ofMinutes(5)
                : retryDelay;
    }

    private OrderDisputeNotificationAudience audience(CreatorRole role) {
        return role == CreatorRole.BUYER
                ? OrderDisputeNotificationAudience.BUYER
                : OrderDisputeNotificationAudience.VENDOR;
    }

    private <T> T inNewTransaction(Supplier<T> work) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> work.get());
    }

    private record Participant(
            OrderDisputeNotificationAudience audience,
            String firstName,
            String email
    ) {
    }

    private record DispatchClaim(
            UUID dispatchToken,
            String recipientEmail,
            String subject,
            String htmlBody
    ) {
    }
}
