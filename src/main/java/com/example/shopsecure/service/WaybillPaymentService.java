package com.example.shopsecure.service;

import com.example.shopsecure.entity.ShippingQuoteStatus;
import com.example.shopsecure.entity.Waybill;
import com.example.shopsecure.entity.WaybillPaymentAttempt;
import com.example.shopsecure.entity.WaybillPaymentAttemptStatus;
import com.example.shopsecure.entity.WaybillPaymentClassification;
import com.example.shopsecure.entity.WaybillPaymentReceipt;
import com.example.shopsecure.entity.WaybillPaymentStatus;
import com.example.shopsecure.entity.WaybillRefund;
import com.example.shopsecure.entity.WaybillRefundStatus;
import com.example.shopsecure.entity.WaybillShippingQuote;
import com.example.shopsecure.entity.WaybillStatus;
import com.example.shopsecure.event.WaybillFundedEvent;
import com.example.shopsecure.provider.payment.PaymentStatusUpdatedEvent;
import com.example.shopsecure.provider.payment.PaymentTransactionStatus;
import com.example.shopsecure.provider.payment.PaymentVerificationResult;
import com.example.shopsecure.repository.WaybillPaymentAttemptRepository;
import com.example.shopsecure.repository.WaybillPaymentReceiptRepository;
import com.example.shopsecure.repository.WaybillRefundRepository;
import com.example.shopsecure.repository.WaybillRepository;
import com.example.shopsecure.repository.WaybillShippingQuoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class WaybillPaymentService {

    private static final Set<WaybillPaymentAttemptStatus> OPEN_ATTEMPT_STATUSES = EnumSet.of(
            WaybillPaymentAttemptStatus.INITIALIZING,
            WaybillPaymentAttemptStatus.INITIALIZATION_UNKNOWN,
            WaybillPaymentAttemptStatus.INITIALIZED
    );

    private final WaybillRepository waybillRepository;
    private final WaybillPaymentAttemptRepository attemptRepository;
    private final WaybillPaymentReceiptRepository receiptRepository;
    private final WaybillRefundRepository refundRepository;
    private final WaybillShippingQuoteRepository quoteRepository;
    private final WaybillAccessService accessService;
    private final WaybillRefundService refundService;
    private final WaybillStatusService statusService;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${waybill.payment-reconciliation.retry-delay:PT5M}")
    private Duration recheckDelay;

    @Transactional(readOnly = true)
    public boolean ownsReference(String reference) {
        return hasText(reference) && attemptRepository.existsByReference(reference);
    }

    @Transactional
    public void handleVerification(PaymentVerificationResult verification) {
        if (verification == null) {
            return;
        }
        handleEvent(new PaymentStatusUpdatedEvent(
                verification.eventId(),
                verification.reference(),
                verification.status(),
                verification.amount(),
                verification.currency(),
                verification.channel(),
                null,
                verification.occurredAt(),
                true
        ), true);
    }

    @Transactional
    public void handleEvent(PaymentStatusUpdatedEvent event) {
        handleEvent(event, false);
    }

    private void handleEvent(PaymentStatusUpdatedEvent event, boolean providerVerification) {
        if (event == null || !event.relevant() || !hasText(event.reference())) {
            return;
        }
        WaybillPaymentAttempt located = attemptRepository.findByReference(event.reference()).orElse(null);
        if (located == null || !located.getReference().equals(event.reference())) {
            return;
        }

        Waybill waybill = waybillRepository.findByIdForUpdate(located.getWaybill().getId()).orElse(null);
        if (waybill == null) {
            return;
        }
        WaybillPaymentAttempt attempt = attemptRepository.findByIdForUpdate(located.getId()).orElse(null);
        if (attempt == null || !attempt.getWaybill().getId().equals(waybill.getId())
                || !attempt.getReference().equals(event.reference())) {
            return;
        }

        PaymentTransactionStatus providerStatus = event.status() == null
                ? PaymentTransactionStatus.UNKNOWN
                : event.status();
        String eventKey = eventKey(attempt, event, providerStatus);
        WaybillPaymentReceipt existingReceipt = receiptRepository.findByProviderEventKey(eventKey)
                .orElse(null);
        if (existingReceipt != null && !canReassess(
                existingReceipt,
                waybill,
                attempt,
                providerStatus,
                providerVerification
        )) {
            return;
        }
        if (providerStatus != PaymentTransactionStatus.SUCCESS) {
            applyNonSuccessfulEvent(waybill, attempt, event, providerStatus, eventKey);
            return;
        }

        CapturedAssessment captured = assessCapturedEvent(attempt, event);
        if (!captured.valid()) {
            recordUnsafeSuccess(
                    waybill,
                    attempt,
                    event,
                    eventKey,
                    existingReceipt,
                    captured.failureReason()
            );
            return;
        }

        SuccessfulAssessment assessment = assessSuccessfulEvent(
                waybill,
                attempt,
                event,
                captured.amount()
        );

        LocalDateTime occurredAt = toLocalDateTime(event.occurredAt());
        attempt.setStatus(WaybillPaymentAttemptStatus.SUCCESS);
        attempt.setProviderTransactionId(event.eventId());
        attempt.setFailureReason(null);
        attempt.setNextRecheckAt(null);
        attempt.setCompletedAt(occurredAt == null
                ? LocalDateTime.now(ZoneOffset.UTC)
                : occurredAt);
        attempt.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        attemptRepository.saveAndFlush(attempt);
        storeReceipt(
                existingReceipt,
                waybill,
                attempt,
                event,
                providerStatus,
                assessment.valid()
                        ? assessment.classification()
                        : WaybillPaymentClassification.FAILED,
                eventKey
        );

        if (!assessment.valid()) {
            queueCapturedRefund(
                    waybill,
                    attempt,
                    captured.amount(),
                    WaybillPaymentClassification.FAILED,
                    assessment.failureReason()
            );
            return;
        }

        if (assessment.classification() == WaybillPaymentClassification.MATCHED) {
            applyMatchedPayment(waybill, attempt, occurredAt);
            return;
        }

        queueCapturedRefund(
                waybill,
                attempt,
                assessment.capturedAmount(),
                assessment.classification(),
                null
        );
    }

    private CapturedAssessment assessCapturedEvent(
            WaybillPaymentAttempt attempt,
            PaymentStatusUpdatedEvent event
    ) {
        if (!hasText(event.eventId()) || event.eventId().length() > 100) {
            return CapturedAssessment.invalid("Successful payment did not include a valid transaction ID");
        }
        if (!attempt.getReference().equals(event.reference())) {
            return CapturedAssessment.invalid("Successful payment reference did not match its attempt");
        }
        if (hasText(attempt.getProviderTransactionId())
                && !attempt.getProviderTransactionId().equals(event.eventId())) {
            return CapturedAssessment.invalid("Payment attempt has a different provider transaction ID");
        }
        BigDecimal capturedAmount = validMoney(event.amount());
        if (capturedAmount == null) {
            return CapturedAssessment.invalid("Successful payment amount was invalid");
        }
        if (!exactCurrency(attempt.getCurrency(), event.currency())) {
            return CapturedAssessment.invalid("Successful payment currency did not match its attempt");
        }
        return CapturedAssessment.valid(capturedAmount);
    }

    private SuccessfulAssessment assessSuccessfulEvent(
            Waybill waybill,
            WaybillPaymentAttempt attempt,
            PaymentStatusUpdatedEvent event,
            BigDecimal capturedAmount
    ) {
        if (attempt.getStatus() == WaybillPaymentAttemptStatus.SUPERSEDED) {
            return SuccessfulAssessment.valid(
                    WaybillPaymentClassification.LATE,
                    capturedAmount
            );
        }

        WaybillShippingQuote quote = frozenQuote(waybill.getId());
        if (quote == null
                || waybill.getTotalAmount() == null
                || waybill.getTotalAmount().signum() <= 0
                || attempt.getAmount() == null
                || attempt.getAmount().compareTo(waybill.getTotalAmount()) != 0
                || quote.getTotalAmount() == null
                || quote.getTotalAmount().compareTo(waybill.getTotalAmount()) != 0
                || !exactCurrency(waybill.getCurrency(), attempt.getCurrency())
                || !exactCurrency(waybill.getCurrency(), quote.getCurrency())) {
            return SuccessfulAssessment.invalid("Waybill frozen payment total was inconsistent");
        }
        if (event.occurredAt() == null || waybill.getPaymentDueAt() == null) {
            return SuccessfulAssessment.invalid("Successful payment time could not be verified");
        }

        LocalDateTime occurredAt = toLocalDateTime(event.occurredAt());
        if (latePaymentContext(waybill)
                || occurredAt.isAfter(waybill.getPaymentDueAt())
                || occurredAt.isAfter(quote.getExpiresAt())) {
            return SuccessfulAssessment.valid(
                    WaybillPaymentClassification.LATE,
                    capturedAmount
            );
        }
        if (hasText(waybill.getFundingPaymentReference())
                && !waybill.getFundingPaymentReference().equals(attempt.getReference())) {
            return SuccessfulAssessment.valid(
                    WaybillPaymentClassification.DUPLICATE,
                    capturedAmount
            );
        }

        int comparison = capturedAmount.compareTo(waybill.getTotalAmount());
        if (comparison < 0) {
            return SuccessfulAssessment.valid(
                    WaybillPaymentClassification.UNDERPAID,
                    capturedAmount
            );
        }
        if (comparison > 0) {
            return SuccessfulAssessment.valid(
                    WaybillPaymentClassification.OVERPAID,
                    capturedAmount
            );
        }
        if (hasText(waybill.getFundingPaymentReference())
                && waybill.getFundingPaymentReference().equals(attempt.getReference())) {
            return SuccessfulAssessment.valid(
                    WaybillPaymentClassification.MATCHED,
                    capturedAmount
            );
        }
        if ((waybill.getStatus() != WaybillStatus.AWAITING_PAYMENT
                && waybill.getStatus() != WaybillStatus.PAYMENT_REVIEW)
                || (waybill.getPaymentStatus() != WaybillPaymentStatus.PENDING
                && waybill.getPaymentStatus() != WaybillPaymentStatus.REVIEW)) {
            return SuccessfulAssessment.invalid("Waybill was not in a fundable payment state");
        }
        return SuccessfulAssessment.valid(WaybillPaymentClassification.MATCHED, capturedAmount);
    }

    private boolean canReassess(
            WaybillPaymentReceipt receipt,
            Waybill waybill,
            WaybillPaymentAttempt attempt,
            PaymentTransactionStatus providerStatus,
            boolean providerVerification
    ) {
        return providerVerification
                && providerStatus == PaymentTransactionStatus.SUCCESS
                && receipt.getProviderStatus() == PaymentTransactionStatus.SUCCESS
                && receipt.getClassification() == WaybillPaymentClassification.FAILED
                && java.util.Objects.equals(receipt.getWaybill().getId(), waybill.getId())
                && java.util.Objects.equals(receipt.getAttempt().getId(), attempt.getId())
                && attempt.getReference().equals(receipt.getReference());
    }

    private void queueCapturedRefund(
            Waybill waybill,
            WaybillPaymentAttempt attempt,
            BigDecimal capturedAmount,
            WaybillPaymentClassification classification,
            String failureReason
    ) {
        String reviewReason = hasText(failureReason)
                ? bounded(failureReason, 500)
                : "Captured waybill payment requires a full refund";
        if (!canSafelyQueueRefund(attempt, capturedAmount)) {
            leavePaymentReview(waybill, reviewReason);
            return;
        }

        boolean primaryOrUnfunded = !hasText(waybill.getFundingPaymentReference())
                || waybill.getFundingPaymentReference().equals(attempt.getReference());
        boolean affectRoot = classification != WaybillPaymentClassification.DUPLICATE
                && primaryOrUnfunded;
        if (affectRoot && !prepareRootForRefund(waybill, reviewReason)) {
            leavePaymentReview(waybill, reviewReason);
            return;
        }
        refundService.queueFullRefund(
                attempt,
                capturedAmount,
                refundReason(classification),
                affectRoot
        );
    }

    private boolean canSafelyQueueRefund(
            WaybillPaymentAttempt attempt,
            BigDecimal capturedAmount
    ) {
        WaybillRefund existing = refundRepository.findByPaymentAttempt_Id(attempt.getId())
                .orElse(null);
        if (existing == null) {
            return true;
        }
        return existing.getAmount().compareTo(capturedAmount) == 0
                && exactCurrency(existing.getCurrency(), attempt.getCurrency())
                && existing.getStatus() != WaybillRefundStatus.FAILED
                && existing.getStatus() != WaybillRefundStatus.MANUAL_REVIEW;
    }

    private boolean prepareRootForRefund(Waybill waybill, String reason) {
        if (waybill.getStatus() == WaybillStatus.AWAITING_QUOTE) {
            leavePaymentReview(waybill, reason);
            return true;
        }
        if (waybill.getStatus() == WaybillStatus.AWAITING_PARTY_DETAILS
                || waybill.getStatus() == WaybillStatus.SHIPMENT_BOOKING
                || waybill.getStatus() == WaybillStatus.READY_FOR_DELIVERY
                || waybill.getStatus() == WaybillStatus.SHIPPING_FAILED
                || waybill.getStatus() == WaybillStatus.COMPLETED
                || waybill.getStatus() == WaybillStatus.REFUNDED) {
            leavePaymentReview(waybill, reason);
            return false;
        }
        return waybill.getStatus() == WaybillStatus.AWAITING_PAYMENT
                || waybill.getStatus() == WaybillStatus.PAYMENT_REVIEW
                || waybill.getStatus() == WaybillStatus.CANCELLATION_PENDING
                || waybill.getStatus() == WaybillStatus.CANCELLED
                || waybill.getStatus() == WaybillStatus.REFUND_PENDING
                || waybill.getStatus() == WaybillStatus.MANUAL_REVIEW;
    }

    private void leavePaymentReview(Waybill waybill, String reason) {
        if (waybill.getStatus() == WaybillStatus.AWAITING_PAYMENT
                || waybill.getStatus() == WaybillStatus.AWAITING_QUOTE) {
            statusService.transition(waybill, WaybillStatus.PAYMENT_REVIEW, reason);
        } else if (waybill.getStatus() == WaybillStatus.AWAITING_PARTY_DETAILS
                || waybill.getStatus() == WaybillStatus.SHIPMENT_BOOKING
                || waybill.getStatus() == WaybillStatus.READY_FOR_DELIVERY
                || waybill.getStatus() == WaybillStatus.SHIPPING_FAILED
                || waybill.getStatus() == WaybillStatus.CANCELLATION_PENDING
                || waybill.getStatus() == WaybillStatus.REFUND_PENDING
                || waybill.getStatus() == WaybillStatus.CANCELLED
                || waybill.getStatus() == WaybillStatus.COMPLETED
                || waybill.getStatus() == WaybillStatus.REFUNDED) {
            statusService.transition(waybill, WaybillStatus.MANUAL_REVIEW, reason);
        }
        waybill.setPaymentStatus(WaybillPaymentStatus.REVIEW);
        waybillRepository.save(waybill);
    }

    private void applyMatchedPayment(
            Waybill waybill,
            WaybillPaymentAttempt attempt,
            LocalDateTime occurredAt
    ) {
        if (hasText(waybill.getFundingPaymentReference())) {
            return;
        }
        waybill.setPaymentStatus(WaybillPaymentStatus.CONFIRMED);
        waybill.setFundingPaymentReference(attempt.getReference());
        waybill.setFundedAt(occurredAt);
        statusService.transition(
                waybill,
                WaybillStatus.SHIPMENT_BOOKING,
                "Exact Paystack waybill payment confirmed"
        );
        waybillRepository.save(waybill);

        if (attempt.getInvitation() != null) {
            accessService.markPaymentCompleted(attempt.getInvitation());
        }
        supersedeOtherAttempts(waybill, attempt);
        eventPublisher.publishEvent(new WaybillFundedEvent(waybill.getId()));
    }

    private void supersedeOtherAttempts(Waybill waybill, WaybillPaymentAttempt fundedAttempt) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        attemptRepository.findByWaybill_IdAndStatusInOrderByCreatedAtDesc(
                        waybill.getId(),
                        OPEN_ATTEMPT_STATUSES
                ).stream()
                .filter(other -> !other.getId().equals(fundedAttempt.getId()))
                .forEach(other -> {
                    other.setStatus(WaybillPaymentAttemptStatus.SUPERSEDED);
                    other.setFailureReason("Another waybill payment attempt funded the waybill");
                    other.setNextRecheckAt(now.plus(validRecheckDelay()));
                    other.setUpdatedAt(now);
                    attemptRepository.save(other);
                });
    }

    private void applyNonSuccessfulEvent(
            Waybill waybill,
            WaybillPaymentAttempt attempt,
            PaymentStatusUpdatedEvent event,
            PaymentTransactionStatus providerStatus,
            String eventKey
    ) {
        storeReceipt(
                null,
                waybill,
                attempt,
                event,
                providerStatus,
                WaybillPaymentClassification.FAILED,
                eventKey
        );
        if (attempt.getStatus() == WaybillPaymentAttemptStatus.SUCCESS) {
            return;
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (providerStatus == PaymentTransactionStatus.FAILED) {
            attempt.setStatus(WaybillPaymentAttemptStatus.FAILED);
            attempt.setFailureReason("Payment provider reported that the charge failed");
            attempt.setCompletedAt(toLocalDateTime(event.occurredAt()) == null
                    ? now
                    : toLocalDateTime(event.occurredAt()));
            attempt.setNextRecheckAt(null);
        } else if (providerStatus == PaymentTransactionStatus.ABANDONED) {
            attempt.setStatus(WaybillPaymentAttemptStatus.ABANDONED);
            attempt.setFailureReason("Payment provider reported that the charge was abandoned");
            attempt.setCompletedAt(toLocalDateTime(event.occurredAt()) == null
                    ? now
                    : toLocalDateTime(event.occurredAt()));
            attempt.setNextRecheckAt(null);
        } else {
            if (providerStatus == PaymentTransactionStatus.PENDING
                    && (attempt.getStatus() == WaybillPaymentAttemptStatus.INITIALIZING
                    || attempt.getStatus() == WaybillPaymentAttemptStatus.INITIALIZATION_UNKNOWN)) {
                attempt.setStatus(WaybillPaymentAttemptStatus.INITIALIZED);
            } else if (attempt.getStatus() == WaybillPaymentAttemptStatus.INITIALIZING) {
                attempt.setStatus(WaybillPaymentAttemptStatus.INITIALIZATION_UNKNOWN);
            }
            attempt.setFailureReason("Payment outcome requires provider verification");
            attempt.setNextRecheckAt(now.plus(validRecheckDelay()));
        }
        attempt.setUpdatedAt(now);
        attemptRepository.save(attempt);
    }

    private void recordUnsafeSuccess(
            Waybill waybill,
            WaybillPaymentAttempt attempt,
            PaymentStatusUpdatedEvent event,
            String eventKey,
            WaybillPaymentReceipt existingReceipt,
            String failureReason
    ) {
        storeReceipt(
                existingReceipt,
                waybill,
                attempt,
                event,
                PaymentTransactionStatus.SUCCESS,
                WaybillPaymentClassification.FAILED,
                eventKey
        );
        if (attempt.getStatus() != WaybillPaymentAttemptStatus.SUCCESS) {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            attempt.setFailureReason(bounded(failureReason, 500));
            attempt.setNextRecheckAt(now.plus(validRecheckDelay()));
            attempt.setUpdatedAt(now);
            attemptRepository.save(attempt);
        }
        if (!hasText(waybill.getFundingPaymentReference())) {
            leavePaymentReview(waybill, "Paystack waybill payment requires verification");
        }
    }

    private void storeReceipt(
            WaybillPaymentReceipt existingReceipt,
            Waybill waybill,
            WaybillPaymentAttempt attempt,
            PaymentStatusUpdatedEvent event,
            PaymentTransactionStatus providerStatus,
            WaybillPaymentClassification classification,
            String eventKey
    ) {
        WaybillPaymentReceipt target = existingReceipt == null
                ? WaybillPaymentReceipt.builder()
                .waybill(waybill)
                .attempt(attempt)
                .providerEventKey(eventKey)
                .reference(attempt.getReference())
                .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                .build()
                : existingReceipt;
        target.setAmount(safeReceiptAmount(event.amount()));
        target.setCurrency(safeCurrency(event.currency()));
        target.setChannel(boundedOrNull(event.channel(), 50));
        target.setProviderStatus(providerStatus);
        target.setClassification(classification);
        target.setOccurredAt(toLocalDateTime(event.occurredAt()));
        receiptRepository.save(target);
    }

    private WaybillShippingQuote frozenQuote(Long waybillId) {
        return quoteRepository.findByWaybillIdAndStatusForUpdate(
                        waybillId,
                        ShippingQuoteStatus.SELECTED
                )
                .orElseGet(() -> quoteRepository.findByWaybillIdAndStatusForUpdate(
                        waybillId,
                        ShippingQuoteStatus.USED
                ).orElse(null));
    }

    private boolean latePaymentContext(Waybill waybill) {
        return waybill.getCancellationRequestedAt() != null
                || waybill.getStatus() == WaybillStatus.CANCELLATION_PENDING
                || waybill.getStatus() == WaybillStatus.CANCELLED
                || waybill.getStatus() == WaybillStatus.REFUND_PENDING
                || waybill.getStatus() == WaybillStatus.REFUNDED
                || waybill.getPaymentStatus() == WaybillPaymentStatus.REFUND_PENDING
                || waybill.getPaymentStatus() == WaybillPaymentStatus.REFUNDED;
    }

    private String refundReason(WaybillPaymentClassification classification) {
        return switch (classification) {
            case UNDERPAID -> "Full refund of underpaid waybill checkout";
            case OVERPAID -> "Full refund of overpaid waybill checkout";
            case DUPLICATE -> "Full refund of duplicate waybill checkout";
            case LATE -> "Full refund of late waybill checkout";
            case MATCHED, FAILED -> "Full refund of waybill checkout";
        };
    }

    private String eventKey(
            WaybillPaymentAttempt attempt,
            PaymentStatusUpdatedEvent event,
            PaymentTransactionStatus providerStatus
    ) {
        if (hasText(event.eventId())) {
            String key = providerStatus == PaymentTransactionStatus.SUCCESS
                    ? event.eventId()
                    : event.eventId() + ":" + providerStatus;
            return boundedEventKey(key);
        }
        return boundedEventKey(attempt.getReference() + ":" + providerStatus);
    }

    private String boundedEventKey(String key) {
        return key.length() <= 160 ? key : sha256(key);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)
            ));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private BigDecimal validMoney(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return null;
        }
        try {
            BigDecimal normalized = amount.setScale(2, RoundingMode.UNNECESSARY);
            return normalized.precision() <= 38 ? normalized : null;
        } catch (ArithmeticException exception) {
            return null;
        }
    }

    private BigDecimal safeReceiptAmount(BigDecimal amount) {
        if (amount == null || amount.signum() < 0) {
            return null;
        }
        try {
            BigDecimal normalized = amount.setScale(2, RoundingMode.UNNECESSARY);
            return normalized.precision() <= 38 ? normalized : null;
        } catch (ArithmeticException exception) {
            return null;
        }
    }

    private String safeCurrency(String currency) {
        return hasText(currency) && currency.length() == 3 ? currency : null;
    }

    private String boundedOrNull(String value, int maxLength) {
        if (!hasText(value)) {
            return null;
        }
        return bounded(value, maxLength);
    }

    private String bounded(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private boolean exactCurrency(String first, String second) {
        return first != null && first.equals(second);
    }

    private Duration validRecheckDelay() {
        return recheckDelay == null || recheckDelay.isNegative() || recheckDelay.isZero()
                ? Duration.ofMinutes(5)
                : recheckDelay;
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record CapturedAssessment(boolean valid, BigDecimal amount, String failureReason) {
        private static CapturedAssessment valid(BigDecimal amount) {
            return new CapturedAssessment(true, amount, null);
        }

        private static CapturedAssessment invalid(String reason) {
            return new CapturedAssessment(false, null, reason);
        }
    }

    private record SuccessfulAssessment(
            boolean valid,
            WaybillPaymentClassification classification,
            BigDecimal capturedAmount,
            String failureReason
    ) {
        private static SuccessfulAssessment valid(
                WaybillPaymentClassification classification,
                BigDecimal capturedAmount
        ) {
            return new SuccessfulAssessment(true, classification, capturedAmount, null);
        }

        private static SuccessfulAssessment invalid(String reason) {
            return new SuccessfulAssessment(false, WaybillPaymentClassification.FAILED, null, reason);
        }
    }
}
