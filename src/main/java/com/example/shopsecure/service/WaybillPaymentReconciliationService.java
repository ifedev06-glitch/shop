package com.example.shopsecure.service;

import com.example.shopsecure.entity.Waybill;
import com.example.shopsecure.entity.WaybillPaymentAttempt;
import com.example.shopsecure.entity.WaybillPaymentAttemptStatus;
import com.example.shopsecure.provider.payment.PaymentProvider;
import com.example.shopsecure.provider.payment.PaymentVerificationResult;
import com.example.shopsecure.repository.WaybillPaymentAttemptRepository;
import com.example.shopsecure.repository.WaybillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class WaybillPaymentReconciliationService {

    private static final int MAX_RECONCILIATION_BATCH_SIZE = 100;
    private static final Set<WaybillPaymentAttemptStatus> RECONCILABLE_STATUSES = EnumSet.of(
            WaybillPaymentAttemptStatus.INITIALIZING,
            WaybillPaymentAttemptStatus.INITIALIZATION_UNKNOWN,
            WaybillPaymentAttemptStatus.INITIALIZED,
            WaybillPaymentAttemptStatus.SUPERSEDED
    );

    private final PaymentProvider paymentProvider;
    private final WaybillPaymentService paymentService;
    private final WaybillRepository waybillRepository;
    private final WaybillPaymentAttemptRepository attemptRepository;
    private final PlatformTransactionManager transactionManager;

    @Value("${waybill.payment-reconciliation.batch-size:50}")
    private int reconciliationBatchSize;

    @Value("${waybill.payment-reconciliation.retry-delay:PT5M}")
    private Duration retryDelay;

    @Scheduled(
            initialDelayString = "${waybill.payment-reconciliation.initial-delay-ms:60000}",
            fixedDelayString = "${waybill.payment-reconciliation.delay-ms:60000}"
    )
    public void reconcilePayments() {
        int batchSize = Math.min(
                MAX_RECONCILIATION_BATCH_SIZE,
                Math.max(1, reconciliationBatchSize)
        );
        List<Long> attemptIds = attemptRepository.findIdsDueForRecheck(
                RECONCILABLE_STATUSES,
                LocalDateTime.now(ZoneOffset.UTC),
                PageRequest.of(0, batchSize)
        );
        attemptIds.forEach(this::reconcileAttemptSafely);
    }

    private void reconcileAttemptSafely(Long attemptId) {
        VerificationTask task = transaction().execute(status -> prepareVerification(attemptId));
        if (task == null) {
            return;
        }

        PaymentVerificationResult verification;
        try {
            verification = withoutTransaction().execute(status ->
                    paymentProvider.verifyTransaction(task.reference())
            );
        } catch (RuntimeException exception) {
            log.warn("Unable to verify waybill payment attempt: attemptId={}", attemptId);
            return;
        }
        if (verification == null || !task.reference().equals(verification.reference())) {
            transaction().executeWithoutResult(status -> markVerificationUnknown(
                    task.attemptId(),
                    task.reference()
            ));
            return;
        }
        try {
            paymentService.handleVerification(verification);
        } catch (RuntimeException exception) {
            log.warn("Unable to apply waybill payment verification: attemptId={}", attemptId);
        }
    }

    private VerificationTask prepareVerification(Long attemptId) {
        WaybillPaymentAttempt located = attemptRepository.findById(attemptId).orElse(null);
        if (located == null) {
            return null;
        }
        Waybill waybill = waybillRepository.findByIdForUpdate(located.getWaybill().getId()).orElse(null);
        if (waybill == null) {
            return null;
        }
        WaybillPaymentAttempt attempt = attemptRepository.findByIdForUpdate(attemptId).orElse(null);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (attempt == null
                || !RECONCILABLE_STATUSES.contains(attempt.getStatus())
                || (attempt.getNextRecheckAt() != null && attempt.getNextRecheckAt().isAfter(now))) {
            return null;
        }
        attempt.setNextRecheckAt(now.plus(validRetryDelay()));
        attempt.setUpdatedAt(now);
        attemptRepository.save(attempt);
        return new VerificationTask(attempt.getId(), attempt.getReference());
    }

    private void markVerificationUnknown(Long attemptId, String expectedReference) {
        WaybillPaymentAttempt located = attemptRepository.findById(attemptId).orElse(null);
        if (located == null) {
            return;
        }
        Waybill waybill = waybillRepository.findByIdForUpdate(located.getWaybill().getId()).orElse(null);
        if (waybill == null) {
            return;
        }
        WaybillPaymentAttempt attempt = attemptRepository.findByIdForUpdate(attemptId).orElse(null);
        if (attempt == null || !expectedReference.equals(attempt.getReference())
                || !RECONCILABLE_STATUSES.contains(attempt.getStatus())) {
            return;
        }
        if (attempt.getStatus() == WaybillPaymentAttemptStatus.INITIALIZING) {
            attempt.setStatus(WaybillPaymentAttemptStatus.INITIALIZATION_UNKNOWN);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        attempt.setFailureReason("Payment verification returned a mismatched provider reference");
        attempt.setNextRecheckAt(now.plus(validRetryDelay()));
        attempt.setUpdatedAt(now);
        attemptRepository.save(attempt);
    }

    private Duration validRetryDelay() {
        return retryDelay == null || retryDelay.isNegative() || retryDelay.isZero()
                ? Duration.ofMinutes(5)
                : retryDelay;
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

    private record VerificationTask(Long attemptId, String reference) {
    }
}
