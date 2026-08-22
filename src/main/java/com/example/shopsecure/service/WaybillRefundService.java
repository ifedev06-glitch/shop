package com.example.shopsecure.service;

import com.example.shopsecure.dto.response.WaybillRefundResponse;
import com.example.shopsecure.entity.Waybill;
import com.example.shopsecure.entity.WaybillPaymentAttempt;
import com.example.shopsecure.entity.WaybillPaymentAttemptStatus;
import com.example.shopsecure.entity.WaybillPaymentStatus;
import com.example.shopsecure.entity.WaybillRefund;
import com.example.shopsecure.entity.WaybillRefundStatus;
import com.example.shopsecure.entity.WaybillStatus;
import com.example.shopsecure.event.WaybillRefundQueuedEvent;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.provider.payment.RefundProvider;
import com.example.shopsecure.provider.payment.RefundRequest;
import com.example.shopsecure.provider.payment.RefundResult;
import com.example.shopsecure.provider.payment.RefundStatus;
import com.example.shopsecure.provider.payment.RefundStatusUpdatedEvent;
import com.example.shopsecure.repository.WaybillPaymentAttemptRepository;
import com.example.shopsecure.repository.WaybillRefundRepository;
import com.example.shopsecure.repository.WaybillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class WaybillRefundService {

    private static final int MAX_RECONCILIATION_BATCH_SIZE = 100;

    private final RefundProvider refundProvider;
    private final WaybillRepository waybillRepository;
    private final WaybillPaymentAttemptRepository attemptRepository;
    private final WaybillRefundRepository refundRepository;
    private final WaybillStatusService statusService;
    private final ApplicationEventPublisher eventPublisher;
    private final PlatformTransactionManager transactionManager;

    @Value("${waybill.refund-reconciliation.batch-size:25}")
    private int reconciliationBatchSize;

    @Value("${waybill.refund-reconciliation.retry-delay:PT5M}")
    private Duration reconciliationRetryDelay;

    @Value("${waybill.refund-reconciliation.unknown-timeout:PT1H}")
    private Duration unknownOutcomeTimeout;

    @Transactional
    public WaybillRefundResponse queueFullRefund(
            WaybillPaymentAttempt paymentAttempt,
            BigDecimal amount,
            String reason,
            boolean affectRoot
    ) {
        if (paymentAttempt == null || paymentAttempt.getId() == null) {
            throw new BusinessException("Waybill payment attempt is required for a refund");
        }
        BigDecimal normalizedAmount = money(amount);

        WaybillPaymentAttempt located = attemptRepository.findById(paymentAttempt.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Waybill payment attempt not found"));
        Waybill waybill = waybillRepository.findByIdForUpdate(located.getWaybill().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Waybill not found"));
        WaybillPaymentAttempt attempt = attemptRepository.findByIdForUpdate(located.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Waybill payment attempt not found"));
        if (attempt.getStatus() != WaybillPaymentAttemptStatus.SUCCESS) {
            throw new BusinessException("Only a captured waybill payment can be refunded");
        }
        String normalizedCurrency = currency(attempt.getCurrency());

        WaybillRefund existing = refundRepository.findByPaymentAttemptIdForUpdate(attempt.getId())
                .orElse(null);
        if (existing != null) {
            if (existing.getAmount().compareTo(normalizedAmount) != 0
                    || !existing.getCurrency().equals(normalizedCurrency)) {
                throw new BusinessException("A different full refund is already queued for this payment");
            }
            if (affectRoot && rootAffecting(waybill, attempt)) {
                if (existing.getStatus() == WaybillRefundStatus.PROCESSED) {
                    applyRootRefundProcessed(waybill, utcNow());
                } else if (existing.getStatus() != WaybillRefundStatus.FAILED
                        && existing.getStatus() != WaybillRefundStatus.MANUAL_REVIEW) {
                    moveRootToRefundPending(waybill, "Waybill payment refund queued");
                }
            }
            if (existing.getStatus() == WaybillRefundStatus.REQUESTED) {
                eventPublisher.publishEvent(new WaybillRefundQueuedEvent(existing.getId()));
            }
            return response(existing);
        }

        LocalDateTime now = utcNow();
        WaybillRefund refund = refundRepository.saveAndFlush(WaybillRefund.builder()
                .waybill(waybill)
                .paymentAttempt(attempt)
                .amount(normalizedAmount)
                .currency(normalizedCurrency)
                .status(WaybillRefundStatus.REQUESTED)
                .reason(refundReason(waybill, attempt, reason))
                .createdAt(now)
                .updatedAt(now)
                .build());
        if (affectRoot && rootAffecting(waybill, attempt)) {
            moveRootToRefundPending(waybill, "Waybill payment refund queued");
        }
        eventPublisher.publishEvent(new WaybillRefundQueuedEvent(refund.getId()));
        return response(refund);
    }

    public void dispatch(Long refundId) {
        PreparedRefund prepared = transaction().execute(status -> prepareDispatch(refundId));
        if (prepared == null) {
            return;
        }

        RefundResult result;
        try {
            result = withoutTransaction().execute(status -> refundProvider.initiateRefund(
                    new RefundRequest(
                            prepared.transactionIdOrReference(),
                            prepared.amount(),
                            prepared.currency(),
                            prepared.customerNote(),
                            merchantNote(prepared.refundId())
                    )
            ));
        } catch (RuntimeException exception) {
            transaction().executeWithoutResult(status -> markDispatchUnknown(prepared.refundId()));
            return;
        }

        try {
            transaction().executeWithoutResult(status -> applyProviderResult(prepared.refundId(), result, true));
        } catch (RuntimeException exception) {
            transaction().executeWithoutResult(status -> markDispatchUnknown(prepared.refundId()));
        }
    }

    @Transactional
    public void handleEvent(RefundStatusUpdatedEvent event) {
        if (event == null || !event.relevant()) {
            return;
        }
        WaybillRefund located = locateRefund(event);
        if (located == null) {
            return;
        }

        Waybill waybill = waybillRepository.findByIdForUpdate(located.getWaybill().getId())
                .orElse(null);
        if (waybill == null) {
            return;
        }
        WaybillPaymentAttempt attempt = attemptRepository.findByIdForUpdate(
                located.getPaymentAttempt().getId()
        ).orElse(null);
        WaybillRefund refund = refundRepository.findByIdForUpdate(located.getId()).orElse(null);
        if (attempt == null || refund == null
                || !refund.getWaybill().getId().equals(waybill.getId())
                || !refund.getPaymentAttempt().getId().equals(attempt.getId())) {
            return;
        }
        if (hasText(event.refundReference())) {
            if (hasText(refund.getProviderRefundReference())
                    && !refund.getProviderRefundReference().equals(event.refundReference())) {
                return;
            }
            refund.setProviderRefundReference(event.refundReference());
        }
        if (hasText(event.transactionReference())
                && !attempt.getReference().equals(event.transactionReference())) {
            return;
        }
        if (!exactRefund(refund, event.amount(), event.currency())) {
            return;
        }

        LocalDateTime providerTime = toLocalDateTime(event.occurredAt());
        if (refund.getStatus() == WaybillRefundStatus.PROCESSED
                && event.status() != RefundStatus.PROCESSED) {
            return;
        }
        if (providerTime != null && refund.getProviderUpdatedAt() != null
                && providerTime.isBefore(refund.getProviderUpdatedAt())) {
            return;
        }
        applyStatus(waybill, attempt, refund, event.status(), providerTime);
        refundRepository.save(refund);
        waybillRepository.save(waybill);
    }

    @Scheduled(
            initialDelayString = "${waybill.refund-reconciliation.initial-delay-ms:60000}",
            fixedDelayString = "${waybill.refund-reconciliation.delay-ms:60000}"
    )
    public void reconcileRefunds() {
        int batchSize = Math.min(
                MAX_RECONCILIATION_BATCH_SIZE,
                Math.max(1, reconciliationBatchSize)
        );
        LocalDateTime updatedBefore = utcNow().minus(validRetryDelay());
        List<Long> refundIds = refundRepository.findIdsDueForReconciliation(
                List.of(
                        WaybillRefundStatus.REQUESTED,
                        WaybillRefundStatus.DISPATCHING,
                        WaybillRefundStatus.DISPATCH_UNKNOWN,
                        WaybillRefundStatus.PENDING,
                        WaybillRefundStatus.PROCESSING
                ),
                updatedBefore,
                PageRequest.of(0, batchSize)
        );
        refundIds.forEach(refundId -> {
            try {
                ReconciliationTask task = transaction().execute(status -> prepareReconciliation(refundId));
                if (task == null) {
                    return;
                }
                if (task.dispatch()) {
                    dispatch(refundId);
                    return;
                }
                if (!hasText(task.providerRefundId())) {
                    return;
                }
                RefundResult result = withoutTransaction().execute(status ->
                        refundProvider.verifyRefund(task.providerRefundId())
                );
                transaction().executeWithoutResult(status -> applyProviderResult(refundId, result, false));
            } catch (RuntimeException exception) {
                log.warn("Unable to reconcile waybill refund: refundId={}", refundId);
            }
        });
    }

    private PreparedRefund prepareDispatch(Long refundId) {
        WaybillRefund located = refundRepository.findById(refundId).orElse(null);
        if (located == null) {
            return null;
        }
        Waybill waybill = waybillRepository.findByIdForUpdate(located.getWaybill().getId())
                .orElse(null);
        if (waybill == null) {
            return null;
        }
        WaybillPaymentAttempt attempt = attemptRepository.findByIdForUpdate(
                located.getPaymentAttempt().getId()
        ).orElse(null);
        WaybillRefund refund = refundRepository.findByIdForUpdate(refundId).orElse(null);
        if (attempt == null || refund == null || refund.getStatus() != WaybillRefundStatus.REQUESTED) {
            return null;
        }
        LocalDateTime now = utcNow();
        refund.setStatus(WaybillRefundStatus.DISPATCHING);
        refund.setFailureReason(null);
        refund.setUpdatedAt(now);
        refundRepository.save(refund);
        String transaction = hasText(attempt.getProviderTransactionId())
                ? attempt.getProviderTransactionId()
                : attempt.getReference();
        return new PreparedRefund(
                refund.getId(),
                transaction,
                refund.getAmount(),
                refund.getCurrency(),
                refund.getReason()
        );
    }

    private void applyProviderResult(Long refundId, RefundResult result, boolean initiation) {
        WaybillRefund located = refundRepository.findById(refundId)
                .orElseThrow(() -> new ResourceNotFoundException("Waybill refund not found"));
        Waybill waybill = waybillRepository.findByIdForUpdate(located.getWaybill().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Waybill not found"));
        WaybillPaymentAttempt attempt = attemptRepository.findByIdForUpdate(
                located.getPaymentAttempt().getId()
        ).orElseThrow(() -> new ResourceNotFoundException("Waybill payment attempt not found"));
        WaybillRefund refund = refundRepository.findByIdForUpdate(refundId)
                .orElseThrow(() -> new ResourceNotFoundException("Waybill refund not found"));

        if (result != null && hasText(result.refundId())) {
            if (hasText(refund.getProviderRefundId())
                    && !refund.getProviderRefundId().equals(result.refundId())) {
                markDispatchUnknown(refund);
                refundRepository.save(refund);
                return;
            }
            refund.setProviderRefundId(result.refundId());
        }
        if (result != null && hasText(result.refundReference())) {
            if (hasText(refund.getProviderRefundReference())
                    && !refund.getProviderRefundReference().equals(result.refundReference())) {
                markDispatchUnknown(refund);
                refundRepository.save(refund);
                return;
            }
            refund.setProviderRefundReference(result.refundReference());
        }
        if (!validProviderResult(refund, attempt, result)) {
            markDispatchUnknown(refund);
            refundRepository.save(refund);
            return;
        }
        LocalDateTime providerTime = toLocalDateTime(result.updatedAt());
        if (initiation && refund.getProviderCreatedAt() == null) {
            refund.setProviderCreatedAt(providerTime == null ? utcNow() : providerTime);
        }
        if (refund.getStatus() != WaybillRefundStatus.PROCESSED
                || result.status() == RefundStatus.PROCESSED) {
            applyStatus(waybill, attempt, refund, result.status(), providerTime);
        }
        refundRepository.save(refund);
        waybillRepository.save(waybill);
    }

    private void applyStatus(
            Waybill waybill,
            WaybillPaymentAttempt attempt,
            WaybillRefund refund,
            RefundStatus providerStatus,
            LocalDateTime providerTime
    ) {
        LocalDateTime now = utcNow();
        RefundStatus status = providerStatus == null ? RefundStatus.UNKNOWN : providerStatus;
        switch (status) {
            case PENDING -> {
                refund.setStatus(WaybillRefundStatus.PENDING);
                refund.setFailureReason(null);
            }
            case PROCESSING -> {
                refund.setStatus(WaybillRefundStatus.PROCESSING);
                refund.setFailureReason(null);
            }
            case PROCESSED -> {
                refund.setStatus(WaybillRefundStatus.PROCESSED);
                refund.setFailureReason(null);
                refund.setCompletedAt(providerTime == null ? now : providerTime);
                if (rootAffecting(waybill, attempt)) {
                    applyRootRefundProcessed(
                            waybill,
                            providerTime == null ? now : providerTime
                    );
                }
            }
            case FAILED -> {
                refund.setStatus(WaybillRefundStatus.FAILED);
                refund.setFailureReason("Payment provider reported that the refund failed");
                if (rootAffecting(waybill, attempt)) {
                    moveRootToManualReview(waybill, "Waybill payment refund failed");
                }
            }
            case NEEDS_ATTENTION -> {
                refund.setStatus(WaybillRefundStatus.MANUAL_REVIEW);
                refund.setFailureReason("Payment provider requires manual refund review");
                if (rootAffecting(waybill, attempt)) {
                    moveRootToManualReview(waybill, "Waybill payment refund requires manual review");
                }
            }
            case UNKNOWN -> {
                refund.setStatus(WaybillRefundStatus.DISPATCH_UNKNOWN);
                refund.setFailureReason("Refund outcome requires provider verification");
            }
        }
        if (providerTime != null) {
            refund.setProviderUpdatedAt(providerTime);
        }
        refund.setUpdatedAt(now);
    }

    private ReconciliationTask prepareReconciliation(Long refundId) {
        WaybillRefund located = refundRepository.findById(refundId).orElse(null);
        if (located == null) {
            return null;
        }
        Waybill waybill = waybillRepository.findByIdForUpdate(located.getWaybill().getId())
                .orElse(null);
        if (waybill == null) {
            return null;
        }
        WaybillPaymentAttempt attempt = attemptRepository.findByIdForUpdate(
                located.getPaymentAttempt().getId()
        ).orElse(null);
        WaybillRefund refund = refundRepository.findByIdForUpdate(refundId).orElse(null);
        if (attempt == null || refund == null) {
            return null;
        }
        if (refund.getStatus() == WaybillRefundStatus.REQUESTED) {
            return new ReconciliationTask(true, null);
        }
        if (refund.getStatus() == WaybillRefundStatus.DISPATCHING) {
            markDispatchUnknown(refund);
        }
        if (refund.getStatus() != WaybillRefundStatus.DISPATCH_UNKNOWN
                && refund.getStatus() != WaybillRefundStatus.PENDING
                && refund.getStatus() != WaybillRefundStatus.PROCESSING) {
            return null;
        }
        LocalDateTime now = utcNow();
        if (refund.getStatus() == WaybillRefundStatus.DISPATCH_UNKNOWN
                && !hasText(refund.getProviderRefundId())
                && !refund.getCreatedAt().plus(validUnknownOutcomeTimeout()).isAfter(now)) {
            refund.setStatus(WaybillRefundStatus.MANUAL_REVIEW);
            refund.setFailureReason("Refund dispatch outcome requires manual provider review");
            refund.setUpdatedAt(now);
            refundRepository.save(refund);
            if (rootAffecting(waybill, attempt)) {
                moveRootToManualReview(
                        waybill,
                        "Waybill refund dispatch outcome requires manual provider review"
                );
            }
            return null;
        }
        refund.setUpdatedAt(now);
        refundRepository.save(refund);
        return new ReconciliationTask(false, refund.getProviderRefundId());
    }

    private WaybillRefund locateRefund(RefundStatusUpdatedEvent event) {
        WaybillRefund refund = null;
        if (hasText(event.refundReference())) {
            refund = refundRepository.findByProviderRefundReference(event.refundReference()).orElse(null);
        }
        if (refund == null && hasText(event.transactionReference())) {
            WaybillPaymentAttempt attempt = attemptRepository.findByReference(
                    event.transactionReference()
            ).orElse(null);
            if (attempt != null) {
                refund = refundRepository.findByPaymentAttempt_Id(attempt.getId()).orElse(null);
            }
        }
        return refund;
    }

    private boolean validProviderResult(
            WaybillRefund refund,
            WaybillPaymentAttempt attempt,
            RefundResult result
    ) {
        if (result == null || !hasText(result.refundId())
                || result.status() == null
                || !exactRefund(refund, result.amount(), result.currency())) {
            return false;
        }
        if (hasText(result.transactionReference())
                && !attempt.getReference().equals(result.transactionReference())
                && !result.transactionReference().equals(attempt.getProviderTransactionId())) {
            return false;
        }
        if (hasText(attempt.getProviderTransactionId())
                && hasText(result.transactionId())
                && !attempt.getProviderTransactionId().equals(result.transactionId())) {
            return false;
        }
        return !hasText(result.merchantNote())
                || merchantNote(refund.getId()).equals(result.merchantNote());
    }

    private boolean exactRefund(WaybillRefund refund, BigDecimal amount, String currency) {
        return amount != null
                && amount.signum() > 0
                && refund.getAmount().compareTo(amount) == 0
                && refund.getCurrency().equals(currency);
    }

    private void markDispatchUnknown(Long refundId) {
        WaybillRefund located = refundRepository.findById(refundId).orElse(null);
        if (located == null) {
            return;
        }
        Waybill waybill = waybillRepository.findByIdForUpdate(located.getWaybill().getId())
                .orElse(null);
        if (waybill == null) {
            return;
        }
        attemptRepository.findByIdForUpdate(located.getPaymentAttempt().getId()).orElse(null);
        WaybillRefund refund = refundRepository.findByIdForUpdate(refundId).orElse(null);
        if (refund == null || refund.getStatus() == WaybillRefundStatus.PROCESSED) {
            return;
        }
        markDispatchUnknown(refund);
        refundRepository.save(refund);
    }

    private void markDispatchUnknown(WaybillRefund refund) {
        refund.setStatus(WaybillRefundStatus.DISPATCH_UNKNOWN);
        refund.setFailureReason("Refund dispatch outcome requires provider verification");
        refund.setUpdatedAt(utcNow());
    }

    private void moveRootToRefundPending(Waybill waybill, String reason) {
        if (waybill.getStatus() == WaybillStatus.REFUNDED
                || waybill.getPaymentStatus() == WaybillPaymentStatus.REFUNDED) {
            return;
        }
        if (waybill.getStatus() == WaybillStatus.AWAITING_PAYMENT) {
            statusService.transition(waybill, WaybillStatus.PAYMENT_REVIEW, reason);
        }
        if (waybill.getStatus() == WaybillStatus.PAYMENT_REVIEW
                || waybill.getStatus() == WaybillStatus.CANCELLATION_PENDING
                || waybill.getStatus() == WaybillStatus.CANCELLED
                || waybill.getStatus() == WaybillStatus.MANUAL_REVIEW) {
            statusService.transition(waybill, WaybillStatus.REFUND_PENDING, reason);
        }
        if (waybill.getStatus() != WaybillStatus.REFUND_PENDING) {
            throw new BusinessException("Waybill is not in a state that permits a payment refund");
        }
        waybill.setPaymentStatus(WaybillPaymentStatus.REFUND_PENDING);
        waybill.setUpdatedAt(utcNow());
        waybillRepository.save(waybill);
    }

    private void applyRootRefundProcessed(Waybill waybill, LocalDateTime completedAt) {
        if (waybill.getStatus() == WaybillStatus.REFUNDED) {
            return;
        }
        moveRootToRefundPending(waybill, "Waybill payment refund confirmed");
        waybill.setPaymentStatus(WaybillPaymentStatus.REFUNDED);
        waybill.setCompletedAt(completedAt);
        statusService.transition(waybill, WaybillStatus.REFUNDED, "Waybill payment refund confirmed");
        waybillRepository.save(waybill);
    }

    private void moveRootToManualReview(Waybill waybill, String reason) {
        waybill.setPaymentStatus(WaybillPaymentStatus.REVIEW);
        if (waybill.getStatus() == WaybillStatus.REFUND_PENDING) {
            statusService.transition(waybill, WaybillStatus.MANUAL_REVIEW, reason);
        }
        waybill.setUpdatedAt(utcNow());
        waybillRepository.save(waybill);
    }

    private boolean rootAffecting(Waybill waybill, WaybillPaymentAttempt attempt) {
        return !hasText(waybill.getFundingPaymentReference())
                || waybill.getFundingPaymentReference().equals(attempt.getReference());
    }

    private String refundReason(
            Waybill waybill,
            WaybillPaymentAttempt attempt,
            String suppliedReason
    ) {
        if (hasText(suppliedReason)) {
            return bounded(suppliedReason, 500);
        }
        if (hasText(waybill.getCancellationReason())
                && rootAffecting(waybill, attempt)) {
            return bounded(waybill.getCancellationReason(), 500);
        }
        return "Full refund for waybill payment " + attempt.getReference();
    }

    private String merchantNote(Long refundId) {
        return "WBREF-" + refundId;
    }

    private BigDecimal money(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException("Refund amount must be greater than zero");
        }
        try {
            BigDecimal normalized = amount.setScale(2, RoundingMode.UNNECESSARY);
            if (normalized.precision() > 38) {
                throw new ArithmeticException("Refund amount is too large");
            }
            return normalized;
        } catch (ArithmeticException exception) {
            throw new BusinessException("Refund amount must be a valid two-decimal currency amount");
        }
    }

    private String currency(String currency) {
        if (!hasText(currency) || currency.length() != 3) {
            throw new BusinessException("Refund currency must be a three-letter code");
        }
        return currency;
    }

    private String bounded(String value, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= maxLength
                ? normalized
                : normalized.substring(0, maxLength);
    }

    private Duration validRetryDelay() {
        return reconciliationRetryDelay == null
                || reconciliationRetryDelay.isNegative()
                || reconciliationRetryDelay.isZero()
                ? Duration.ofMinutes(5)
                : reconciliationRetryDelay;
    }

    private Duration validUnknownOutcomeTimeout() {
        return unknownOutcomeTimeout == null
                || unknownOutcomeTimeout.isNegative()
                || unknownOutcomeTimeout.isZero()
                ? Duration.ofHours(1)
                : unknownOutcomeTimeout;
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private TransactionTemplate transaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction;
    }

    private TransactionTemplate withoutTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        return transaction;
    }

    private WaybillRefundResponse response(WaybillRefund refund) {
        return new WaybillRefundResponse(
                refund.getId(),
                refund.getProviderRefundId(),
                refund.getProviderRefundReference(),
                refund.getAmount(),
                refund.getCurrency(),
                refund.getStatus(),
                refund.getReason(),
                refund.getFailureReason(),
                refund.getProviderCreatedAt(),
                refund.getProviderUpdatedAt(),
                refund.getCreatedAt(),
                refund.getUpdatedAt(),
                refund.getCompletedAt()
        );
    }

    private record PreparedRefund(
            Long refundId,
            String transactionIdOrReference,
            BigDecimal amount,
            String currency,
            String customerNote
    ) {
    }

    private record ReconciliationTask(boolean dispatch, String providerRefundId) {
    }
}
