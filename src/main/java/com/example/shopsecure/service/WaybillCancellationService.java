package com.example.shopsecure.service;

import com.example.shopsecure.dto.response.WaybillCancellationResponse;
import com.example.shopsecure.dto.response.WaybillRefundResponse;
import com.example.shopsecure.entity.User;
import com.example.shopsecure.entity.Waybill;
import com.example.shopsecure.entity.WaybillPaymentAttempt;
import com.example.shopsecure.entity.WaybillPaymentAttemptStatus;
import com.example.shopsecure.entity.WaybillPaymentReceipt;
import com.example.shopsecure.entity.WaybillPaymentStatus;
import com.example.shopsecure.entity.WaybillRefund;
import com.example.shopsecure.entity.WaybillRefundStatus;
import com.example.shopsecure.entity.WaybillShipment;
import com.example.shopsecure.entity.WaybillStatus;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.provider.shipping.ShipmentResponse;
import com.example.shopsecure.provider.shipping.ShipmentStatus;
import com.example.shopsecure.provider.payment.PaymentTransactionStatus;
import com.example.shopsecure.repository.WaybillPaymentAttemptRepository;
import com.example.shopsecure.repository.WaybillPaymentReceiptRepository;
import com.example.shopsecure.repository.WaybillRefundRepository;
import com.example.shopsecure.repository.WaybillRepository;
import com.example.shopsecure.repository.WaybillShipmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class WaybillCancellationService {

    private static final int MAX_RECONCILIATION_BATCH_SIZE = 100;
    private static final int DEFAULT_MAX_CANCELLATION_ATTEMPTS = 5;
    private static final Duration DEFAULT_CANCELLATION_RETRY_DELAY = Duration.ofMinutes(5);
    private static final String PROVIDER_FAILURE_REASON = "Carrier cancellation request failed";
    private static final Set<WaybillStatus> CANCELLABLE_ROOT_STATUSES = EnumSet.of(
            WaybillStatus.AWAITING_PARTY_DETAILS,
            WaybillStatus.AWAITING_QUOTE,
            WaybillStatus.AWAITING_PAYMENT,
            WaybillStatus.PAYMENT_REVIEW,
            WaybillStatus.SHIPMENT_BOOKING,
            WaybillStatus.READY_FOR_DELIVERY,
            WaybillStatus.SHIPPING_FAILED,
            WaybillStatus.MANUAL_REVIEW
    );
    private static final Set<ShipmentStatus> CANCELLABLE_SHIPMENT_STATUSES = EnumSet.of(
            ShipmentStatus.DRAFT,
            ShipmentStatus.PENDING,
            ShipmentStatus.CONFIRMED
    );

    private final WaybillRepository waybillRepository;
    private final WaybillShipmentRepository shipmentRepository;
    private final WaybillPaymentAttemptRepository attemptRepository;
    private final WaybillPaymentReceiptRepository receiptRepository;
    private final WaybillRefundRepository refundRepository;
    private final WaybillShippingService shippingService;
    private final WaybillAccessService accessService;
    private final WaybillRefundService refundService;
    private final WaybillStatusService statusService;
    private final OrderService orderService;
    private final PlatformTransactionManager transactionManager;

    @Value("${waybill.cancellation-reconciliation.batch-size:25}")
    private int reconciliationBatchSize;

    @Value("${waybill.cancellation-reconciliation.max-attempts:5}")
    private int maxCancellationAttempts;

    @Value("${waybill.cancellation-reconciliation.retry-delay:PT5M}")
    private Duration cancellationRetryDelay;

    public WaybillCancellationResponse request(
            String publicId,
            String idempotencyKey,
            String reason
    ) {
        String normalizedPublicId = requiredText(publicId, "Waybill public ID", 36);
        String normalizedKey = requiredText(idempotencyKey, "Idempotency-Key", 255);
        String normalizedReason = optionalText(reason, 500);
        User creator = orderService.currentUser();

        PreparedCancellation prepared = requireResult(transaction().execute(status -> prepareRequest(
                normalizedPublicId,
                creator,
                normalizedKey,
                normalizedReason
        )));
        return executePrepared(prepared);
    }

    @Scheduled(
            initialDelayString = "${waybill.cancellation-reconciliation.initial-delay-ms:60000}",
            fixedDelayString = "${waybill.cancellation-reconciliation.delay-ms:60000}"
    )
    public void reconcileCancellations() {
        int batchSize = Math.min(
                MAX_RECONCILIATION_BATCH_SIZE,
                Math.max(1, reconciliationBatchSize)
        );
        List<Long> waybillIds = waybillRepository.findWaybillIdsDueForCancellation(
                WaybillStatus.CANCELLATION_PENDING,
                CANCELLABLE_SHIPMENT_STATUSES,
                utcNow(),
                PageRequest.of(0, batchSize)
        );
        waybillIds.forEach(this::reconcileCancellationSafely);
    }

    public void finalizeCarrierCancellation(Long waybillId) {
        if (waybillId == null) {
            return;
        }
        transaction().executeWithoutResult(status -> {
            Waybill waybill = waybillRepository.findByIdForUpdate(waybillId).orElse(null);
            if (waybill == null
                    || waybill.getStatus() != WaybillStatus.CANCELLATION_PENDING) {
                return;
            }
            WaybillShipment shipment = shipmentRepository.findByWaybillIdForUpdate(waybillId)
                    .orElse(null);
            if (shipment == null || shipment.getStatus() != ShipmentStatus.CANCELLED) {
                return;
            }
            applyCancelledLocked(waybill, shipment, null);
        });
    }

    private void reconcileCancellationSafely(Long waybillId) {
        try {
            PreparedCancellation prepared = transaction().execute(status ->
                    prepareReconciliation(waybillId)
            );
            if (prepared != null) {
                executePrepared(prepared);
            }
        } catch (RuntimeException exception) {
            log.warn("Unable to reconcile waybill cancellation: waybillId={}", waybillId);
        }
    }

    private PreparedCancellation prepareRequest(
            String publicId,
            User creator,
            String idempotencyKey,
            String reason
    ) {
        Waybill waybill = waybillRepository.findByPublicIdForUpdate(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Waybill not found"));
        requireCreator(waybill, creator);
        WaybillShipment shipment = shipmentRepository.findByWaybillIdForUpdate(waybill.getId())
                .orElse(null);

        if (waybill.getCancellationIdempotencyKey() != null) {
            if (!waybill.getCancellationIdempotencyKey().equals(idempotencyKey)) {
                throw new BusinessException("A different idempotency key already requested cancellation");
            }
            if (waybill.getStatus() == WaybillStatus.CANCELLATION_PENDING) {
                return preparePendingCancellation(waybill, shipment, utcNow());
            }
            return PreparedCancellation.replay(response(waybill));
        }

        if (waybill.getStatus() == WaybillStatus.COMPLETED
                || waybill.getStatus() == WaybillStatus.REFUNDED) {
            throw new BusinessException("Waybill can no longer be cancelled");
        }
        if (waybill.getStatus() == WaybillStatus.MANUAL_REVIEW
                && (waybill.getPaymentStatus() != WaybillPaymentStatus.CONFIRMED
                || shipment == null
                || !CANCELLABLE_SHIPMENT_STATUSES.contains(shipment.getStatus()))) {
            throw new BusinessException("Waybill requires manual review before cancellation");
        }
        if (shipment != null && (shipment.getStatus() == ShipmentStatus.IN_TRANSIT
                || shipment.getStatus() == ShipmentStatus.DELIVERED)) {
            throw new BusinessException("An in-transit or delivered shipment cannot be cancelled");
        }
        if (!CANCELLABLE_ROOT_STATUSES.contains(waybill.getStatus())) {
            throw new BusinessException("Waybill is not in a cancellable state");
        }

        LocalDateTime now = utcNow();
        waybill.setCancellationIdempotencyKey(idempotencyKey);
        waybill.setCancellationReason(reason);
        waybill.setCancellationRequestedAt(now);
        statusService.transition(
                waybill,
                WaybillStatus.CANCELLATION_PENDING,
                "Creator requested waybill cancellation"
        );
        accessService.revokeAll(waybill);
        waybillRepository.save(waybill);

        return preparePendingCancellation(waybill, shipment, now);
    }

    private PreparedCancellation prepareReconciliation(Long waybillId) {
        Waybill waybill = waybillRepository.findByIdForUpdate(waybillId).orElse(null);
        if (waybill == null || waybill.getStatus() != WaybillStatus.CANCELLATION_PENDING) {
            return null;
        }
        WaybillShipment shipment = shipmentRepository.findByWaybillIdForUpdate(waybillId)
                .orElse(null);
        return preparePendingCancellation(waybill, shipment, utcNow());
    }

    private PreparedCancellation preparePendingCancellation(
            Waybill waybill,
            WaybillShipment shipment,
            LocalDateTime now
    ) {
        String idempotencyKey = waybill.getCancellationIdempotencyKey();
        if (shipment == null || shipment.getStatus() == ShipmentStatus.CANCELLED) {
            return PreparedCancellation.finalize(waybill.getId(), idempotencyKey);
        }
        if (!CANCELLABLE_SHIPMENT_STATUSES.contains(shipment.getStatus())) {
            moveToManualReview(
                    waybill,
                    shipment,
                    "Carrier shipment state is not safe for automated cancellation"
            );
            return PreparedCancellation.replay(response(waybill));
        }
        if (shipment.getNextCancellationAttemptAt() != null
                && shipment.getNextCancellationAttemptAt().isAfter(now)) {
            return PreparedCancellation.replay(response(waybill));
        }
        if (shipment.getCancellationAttempt() >= validMaxCancellationAttempts()) {
            moveToManualReview(
                    waybill,
                    shipment,
                    "Automated carrier cancellation retry limit reached"
            );
            return PreparedCancellation.replay(response(waybill));
        }

        int attempt = Math.max(0, shipment.getCancellationAttempt()) + 1;
        shipment.setCancellationAttempt(attempt);
        shipment.setNextCancellationAttemptAt(nextAttemptAt(now, attempt));
        shipment.setUpdatedAt(now);
        shipmentRepository.save(shipment);
        return PreparedCancellation.dispatch(waybill.getId(), idempotencyKey);
    }

    private WaybillCancellationResponse executePrepared(PreparedCancellation prepared) {
        if (prepared.action() == CancellationAction.REPLAY) {
            return requireResult(prepared.replayResponse());
        }
        if (prepared.action() == CancellationAction.FINALIZE) {
            return requireResult(transaction().execute(status -> applyCancelled(
                    prepared.waybillId(),
                    prepared.idempotencyKey(),
                    null
            )));
        }

        ShipmentResponse providerResponse;
        try {
            providerResponse = shippingService.cancelProviderShipment(prepared.waybillId());
        } catch (RuntimeException exception) {
            return requireResult(transaction().execute(status -> applyProviderFailure(
                    prepared.waybillId(),
                    prepared.idempotencyKey(),
                    PROVIDER_FAILURE_REASON
            )));
        }
        if (providerResponse != null && providerResponse.status() == ShipmentStatus.CANCELLED) {
            return requireResult(transaction().execute(status -> applyCancelled(
                    prepared.waybillId(),
                    prepared.idempotencyKey(),
                    providerResponse
            )));
        }
        return requireResult(transaction().execute(status -> applyNonCancelledResponse(
                prepared.waybillId(),
                prepared.idempotencyKey(),
                providerResponse
        )));
    }

    private WaybillCancellationResponse applyCancelled(
            Long waybillId,
            String idempotencyKey,
            ShipmentResponse providerResponse
    ) {
        Waybill waybill = requireWaybill(waybillId, idempotencyKey);
        WaybillShipment shipment = shipmentRepository.findByWaybillIdForUpdate(waybillId).orElse(null);
        return applyCancelledLocked(waybill, shipment, providerResponse);
    }

    private WaybillCancellationResponse applyCancelledLocked(
            Waybill waybill,
            WaybillShipment shipment,
            ShipmentResponse providerResponse
    ) {
        if (waybill.getStatus() == WaybillStatus.REFUND_PENDING
                || waybill.getStatus() == WaybillStatus.REFUNDED
                || waybill.getStatus() == WaybillStatus.CANCELLED) {
            clearSuccessfulRetryMetadata(shipment);
            return response(waybill);
        }
        if (waybill.getStatus() != WaybillStatus.CANCELLATION_PENDING) {
            clearTerminalRetryMetadata(waybill, shipment);
            return response(waybill);
        }
        if (shipment != null && providerResponse != null
                && (providerResponse.status() != ShipmentStatus.CANCELLED
                || !Objects.equals(
                        shipment.getProviderShipmentId(),
                        providerResponse.shipmentId()
                ))) {
            moveToManualReview(
                    waybill,
                    shipment,
                    "Carrier returned a mismatched cancellation response"
            );
            return response(waybill);
        }
        if (shipment != null && shipment.getStatus() != ShipmentStatus.CANCELLED) {
            return retainPendingOrExhaust(
                    waybill,
                    shipment,
                    "Carrier cancellation confirmation was not persisted"
            );
        }
        clearSuccessfulRetryMetadata(shipment);

        SuccessReceiptReconciliation receiptReconciliation = reconcileSuccessReceipts(waybill);
        if (!receiptReconciliation.safe()) {
            return response(waybill);
        }

        WaybillPaymentAttempt primaryPayment = primaryPayment(waybill);
        if (primaryPayment == null && waybill.getFundingPaymentReference() != null) {
            markCancellationPaymentReview(
                    waybill,
                    "Waybill funding reference could not be reconciled during cancellation"
            );
            return response(waybill);
        }

        WaybillRefundResponse refund = receiptReconciliation.refund();
        if (primaryPayment != null
                && !receiptReconciliation.attemptIds().contains(primaryPayment.getId())) {
            if (!canSafelyQueueRefund(primaryPayment, primaryPayment.getAmount())) {
                markCancellationPaymentReview(
                        waybill,
                        "Captured waybill payment could not be safely refunded during cancellation"
                );
                return response(waybill);
            }
            refund = refundService.queueFullRefund(
                    primaryPayment,
                    primaryPayment.getAmount(),
                    waybill.getCancellationReason(),
                    true
            );
        }
        if (refund != null) {
            return response(waybill, refund);
        }

        statusService.transition(
                waybill,
                WaybillStatus.CANCELLED,
                "Carrier cancellation confirmed"
        );
        waybillRepository.save(waybill);
        return response(waybill);
    }

    private WaybillCancellationResponse applyNonCancelledResponse(
            Long waybillId,
            String idempotencyKey,
            ShipmentResponse providerResponse
    ) {
        Waybill waybill = requireWaybill(waybillId, idempotencyKey);
        WaybillShipment shipment = shipmentRepository.findByWaybillIdForUpdate(waybillId).orElse(null);
        if (waybill.getStatus() != WaybillStatus.CANCELLATION_PENDING) {
            clearTerminalRetryMetadata(waybill, shipment);
            return response(waybill);
        }
        if (shipment == null || shipment.getStatus() == ShipmentStatus.CANCELLED) {
            return applyCancelledLocked(waybill, shipment, null);
        }
        if (!CANCELLABLE_SHIPMENT_STATUSES.contains(shipment.getStatus())) {
            moveToManualReview(
                    waybill,
                    shipment,
                    "Carrier shipment state is not safe for automated cancellation"
            );
            return response(waybill);
        }

        boolean mismatchedShipment = providerResponse == null
                || providerResponse.shipmentId() == null
                || !providerResponse.shipmentId().equals(shipment.getProviderShipmentId());
        ShipmentStatus providerStatus = providerResponse == null ? null : providerResponse.status();
        if (mismatchedShipment
                || providerStatus == null
                || providerStatus == ShipmentStatus.IN_TRANSIT
                || providerStatus == ShipmentStatus.DELIVERED
                || providerStatus == ShipmentStatus.FAILED
                || providerStatus == ShipmentStatus.UNKNOWN) {
            moveToManualReview(
                    waybill,
                    shipment,
                    "Carrier did not confirm waybill cancellation safely"
            );
            return response(waybill);
        }
        return retainPendingOrExhaust(
                waybill,
                shipment,
                "Carrier has not confirmed waybill cancellation"
        );
    }

    private WaybillCancellationResponse applyProviderFailure(
            Long waybillId,
            String idempotencyKey,
            String failureReason
    ) {
        Waybill waybill = requireWaybill(waybillId, idempotencyKey);
        WaybillShipment shipment = shipmentRepository.findByWaybillIdForUpdate(waybillId).orElse(null);
        if (waybill.getStatus() != WaybillStatus.CANCELLATION_PENDING) {
            clearTerminalRetryMetadata(waybill, shipment);
            return response(waybill);
        }
        if (shipment == null || shipment.getStatus() == ShipmentStatus.CANCELLED) {
            return applyCancelledLocked(waybill, shipment, null);
        }
        if (!CANCELLABLE_SHIPMENT_STATUSES.contains(shipment.getStatus())) {
            moveToManualReview(
                    waybill,
                    shipment,
                    "Carrier shipment state is not safe for automated cancellation"
            );
            return response(waybill);
        }
        return retainPendingOrExhaust(waybill, shipment, failureReason);
    }

    private WaybillCancellationResponse retainPendingOrExhaust(
            Waybill waybill,
            WaybillShipment shipment,
            String failureReason
    ) {
        if (shipment.getCancellationAttempt() >= validMaxCancellationAttempts()) {
            moveToManualReview(
                    waybill,
                    shipment,
                    bounded(failureReason + "; automated retry limit reached", 500)
            );
            return response(waybill);
        }

        LocalDateTime now = utcNow();
        shipment.setCancellationFailureReason(bounded(failureReason, 500));
        shipment.setNextCancellationAttemptAt(nextAttemptAt(
                now,
                shipment.getCancellationAttempt()
        ));
        shipment.setUpdatedAt(now);
        shipmentRepository.save(shipment);
        return response(waybill);
    }

    private void moveToManualReview(
            Waybill waybill,
            WaybillShipment shipment,
            String reason
    ) {
        LocalDateTime now = utcNow();
        if (shipment != null) {
            shipment.setNextCancellationAttemptAt(null);
            shipment.setCancellationFailureReason(bounded(reason, 500));
            shipment.setUpdatedAt(now);
            shipmentRepository.save(shipment);
        }
        if (waybill.getStatus() == WaybillStatus.CANCELLATION_PENDING) {
            statusService.transition(waybill, WaybillStatus.MANUAL_REVIEW, bounded(reason, 500));
            waybillRepository.save(waybill);
        }
    }

    private void clearSuccessfulRetryMetadata(WaybillShipment shipment) {
        if (shipment == null
                || (shipment.getNextCancellationAttemptAt() == null
                && shipment.getCancellationFailureReason() == null)) {
            return;
        }
        shipment.setNextCancellationAttemptAt(null);
        shipment.setCancellationFailureReason(null);
        shipment.setUpdatedAt(utcNow());
        shipmentRepository.save(shipment);
    }

    private void clearTerminalRetryMetadata(Waybill waybill, WaybillShipment shipment) {
        if (waybill.getStatus() == WaybillStatus.CANCELLED
                || waybill.getStatus() == WaybillStatus.REFUND_PENDING
                || waybill.getStatus() == WaybillStatus.REFUNDED) {
            clearSuccessfulRetryMetadata(shipment);
            return;
        }
        if (shipment == null || shipment.getNextCancellationAttemptAt() == null) {
            return;
        }
        shipment.setNextCancellationAttemptAt(null);
        shipment.setUpdatedAt(utcNow());
        shipmentRepository.save(shipment);
    }

    private SuccessReceiptReconciliation reconcileSuccessReceipts(Waybill waybill) {
        List<WaybillPaymentReceipt> receipts = receiptRepository
                .findByWaybill_IdAndProviderStatusOrderByCreatedAtAsc(
                        waybill.getId(),
                        PaymentTransactionStatus.SUCCESS
                );
        if (receipts == null || receipts.isEmpty()) {
            return SuccessReceiptReconciliation.safe(Set.of(), null);
        }

        Map<Long, CapturedPayment> capturedPayments = new LinkedHashMap<>();
        for (WaybillPaymentReceipt receipt : receipts) {
            if (receipt == null
                    || receipt.getAttempt() == null
                    || receipt.getAttempt().getId() == null
                    || receipt.getWaybill() == null
                    || !waybill.getId().equals(receipt.getWaybill().getId())) {
                markCancellationPaymentReview(
                        waybill,
                        "Successful payment receipt could not be reconciled during cancellation"
                );
                return SuccessReceiptReconciliation.unsafe();
            }
            WaybillPaymentAttempt attempt = attemptRepository.findByIdForUpdate(
                    receipt.getAttempt().getId()
            ).orElse(null);
            BigDecimal amount = validMoney(receipt.getAmount());
            if (attempt == null
                    || attempt.getStatus() != WaybillPaymentAttemptStatus.SUCCESS
                    || !waybill.getId().equals(attempt.getWaybill().getId())
                    || !hasText(attempt.getProviderTransactionId())
                    || !attempt.getReference().equals(receipt.getReference())
                    || amount == null
                    || !attempt.getCurrency().equals(receipt.getCurrency())) {
                markCancellationPaymentReview(
                        waybill,
                        "Successful payment receipt requires reconciliation before cancellation"
                );
                return SuccessReceiptReconciliation.unsafe();
            }
            CapturedPayment existing = capturedPayments.get(attempt.getId());
            if (existing != null && existing.amount().compareTo(amount) != 0) {
                markCancellationPaymentReview(
                        waybill,
                        "Successful payment receipts disagree on the captured amount"
                );
                return SuccessReceiptReconciliation.unsafe();
            }
            capturedPayments.putIfAbsent(attempt.getId(), new CapturedPayment(attempt, amount));
        }

        for (CapturedPayment captured : capturedPayments.values()) {
            if (!canSafelyQueueRefund(captured.attempt(), captured.amount())) {
                markCancellationPaymentReview(
                        waybill,
                        "Captured waybill payment could not be safely refunded during cancellation"
                );
                return SuccessReceiptReconciliation.unsafe();
            }
        }

        WaybillRefundResponse selectedRefund = null;
        for (CapturedPayment captured : capturedPayments.values()) {
            boolean affectRoot = !hasText(waybill.getFundingPaymentReference())
                    || waybill.getFundingPaymentReference().equals(captured.attempt().getReference());
            WaybillRefundResponse queued = refundService.queueFullRefund(
                    captured.attempt(),
                    captured.amount(),
                    waybill.getCancellationReason(),
                    affectRoot
            );
            if (selectedRefund == null || affectRoot) {
                selectedRefund = queued;
            }
        }
        return SuccessReceiptReconciliation.safe(
                Set.copyOf(capturedPayments.keySet()),
                selectedRefund
        );
    }

    private boolean canSafelyQueueRefund(WaybillPaymentAttempt attempt, BigDecimal amount) {
        BigDecimal normalizedAmount = validMoney(amount);
        if (normalizedAmount == null || !hasText(attempt.getCurrency())) {
            return false;
        }
        WaybillRefund existing = refundRepository.findByPaymentAttempt_Id(attempt.getId())
                .orElse(null);
        if (existing == null) {
            return true;
        }
        return existing.getAmount().compareTo(normalizedAmount) == 0
                && attempt.getCurrency().equals(existing.getCurrency())
                && existing.getStatus() != WaybillRefundStatus.FAILED
                && existing.getStatus() != WaybillRefundStatus.MANUAL_REVIEW;
    }

    private void markCancellationPaymentReview(Waybill waybill, String reason) {
        waybill.setPaymentStatus(WaybillPaymentStatus.REVIEW);
        if (waybill.getStatus() == WaybillStatus.CANCELLATION_PENDING) {
            statusService.transition(waybill, WaybillStatus.MANUAL_REVIEW, reason);
        } else if (waybill.getStatus() == WaybillStatus.REFUND_PENDING) {
            statusService.transition(waybill, WaybillStatus.MANUAL_REVIEW, reason);
        }
        waybillRepository.save(waybill);
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

    private WaybillPaymentAttempt primaryPayment(Waybill waybill) {
        if (waybill.getFundingPaymentReference() == null) {
            return null;
        }
        return attemptRepository.findByReferenceForUpdate(waybill.getFundingPaymentReference())
                .filter(attempt -> attempt.getStatus() == WaybillPaymentAttemptStatus.SUCCESS)
                .orElse(null);
    }

    private Waybill requireWaybill(Long waybillId, String idempotencyKey) {
        Waybill waybill = waybillRepository.findByIdForUpdate(waybillId)
                .orElseThrow(() -> new ResourceNotFoundException("Waybill not found"));
        if (!Objects.equals(idempotencyKey, waybill.getCancellationIdempotencyKey())) {
            throw new BusinessException("Waybill cancellation request changed during processing");
        }
        return waybill;
    }

    private void requireCreator(Waybill waybill, User creator) {
        if (!waybill.getCreator().getId().equals(creator.getId())) {
            throw new AccessDeniedException("Waybill does not belong to current user");
        }
    }

    private WaybillCancellationResponse response(Waybill waybill) {
        return response(waybill, currentRefund(waybill));
    }

    private WaybillCancellationResponse response(
            Waybill waybill,
            WaybillRefundResponse refund
    ) {
        return new WaybillCancellationResponse(
                waybill.getPublicId(),
                waybill.getStatus(),
                waybill.getPaymentStatus(),
                waybill.getCancellationReason(),
                waybill.getCancellationRequestedAt(),
                refund
        );
    }

    private WaybillRefundResponse currentRefund(Waybill waybill) {
        List<WaybillRefund> refunds = refundRepository.findByWaybill_IdOrderByCreatedAtDesc(
                waybill.getId()
        );
        for (WaybillRefund refund : refunds) {
            if (waybill.getFundingPaymentReference() == null
                    || waybill.getFundingPaymentReference().equals(
                    refund.getPaymentAttempt().getReference()
            )) {
                return refundResponse(refund);
            }
        }
        return null;
    }

    private WaybillRefundResponse refundResponse(WaybillRefund refund) {
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

    private TransactionTemplate transaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction;
    }

    private int validMaxCancellationAttempts() {
        return maxCancellationAttempts < 1
                ? DEFAULT_MAX_CANCELLATION_ATTEMPTS
                : maxCancellationAttempts;
    }

    private Duration validCancellationRetryDelay() {
        return cancellationRetryDelay == null
                || cancellationRetryDelay.isZero()
                || cancellationRetryDelay.isNegative()
                ? DEFAULT_CANCELLATION_RETRY_DELAY
                : cancellationRetryDelay;
    }

    private LocalDateTime nextAttemptAt(LocalDateTime now, int attempt) {
        int boundedAttempt = Math.min(
                Math.max(1, attempt),
                validMaxCancellationAttempts()
        );
        return now.plus(validCancellationRetryDelay().multipliedBy(boundedAttempt));
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private String bounded(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String requiredText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(field + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new BusinessException(field + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }

    private String optionalText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new BusinessException("Cancellation reason must not exceed " + maxLength + " characters");
        }
        return normalized;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private <T> T requireResult(T result) {
        if (result == null) {
            throw new IllegalStateException("Waybill cancellation transaction returned no result");
        }
        return result;
    }

    private record CapturedPayment(WaybillPaymentAttempt attempt, BigDecimal amount) {
    }

    private record SuccessReceiptReconciliation(
            boolean safe,
            Set<Long> attemptIds,
            WaybillRefundResponse refund
    ) {
        private static SuccessReceiptReconciliation safe(
                Set<Long> attemptIds,
                WaybillRefundResponse refund
        ) {
            return new SuccessReceiptReconciliation(true, attemptIds, refund);
        }

        private static SuccessReceiptReconciliation unsafe() {
            return new SuccessReceiptReconciliation(false, Set.of(), null);
        }
    }

    private enum CancellationAction {
        DISPATCH,
        FINALIZE,
        REPLAY
    }

    private record PreparedCancellation(
            Long waybillId,
            String idempotencyKey,
            CancellationAction action,
            WaybillCancellationResponse replayResponse
    ) {
        private static PreparedCancellation dispatch(Long waybillId, String idempotencyKey) {
            return new PreparedCancellation(
                    waybillId,
                    idempotencyKey,
                    CancellationAction.DISPATCH,
                    null
            );
        }

        private static PreparedCancellation finalize(Long waybillId, String idempotencyKey) {
            return new PreparedCancellation(
                    waybillId,
                    idempotencyKey,
                    CancellationAction.FINALIZE,
                    null
            );
        }

        private static PreparedCancellation replay(WaybillCancellationResponse response) {
            return new PreparedCancellation(null, null, CancellationAction.REPLAY, response);
        }
    }
}
