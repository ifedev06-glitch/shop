package com.example.shopsecure.repository;

import com.example.shopsecure.entity.WaybillPaymentReceipt;
import com.example.shopsecure.provider.payment.PaymentTransactionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WaybillPaymentReceiptRepository extends JpaRepository<WaybillPaymentReceipt, Long> {

    boolean existsByProviderEventKey(String providerEventKey);

    Optional<WaybillPaymentReceipt> findByProviderEventKey(String providerEventKey);

    boolean existsByWaybill_IdAndProviderStatus(
            Long waybillId,
            PaymentTransactionStatus providerStatus
    );

    List<WaybillPaymentReceipt> findByWaybill_IdAndProviderStatusOrderByCreatedAtAsc(
            Long waybillId,
            PaymentTransactionStatus providerStatus
    );

    List<WaybillPaymentReceipt> findByWaybill_IdOrderByCreatedAtDesc(Long waybillId);

    Optional<WaybillPaymentReceipt> findTopByWaybill_IdOrderByCreatedAtDesc(Long waybillId);

    List<WaybillPaymentReceipt> findByAttempt_IdOrderByCreatedAtAsc(Long attemptId);
}
