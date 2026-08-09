package com.example.shopsecure.repository;

import com.example.shopsecure.entity.PaymentReceipt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import com.example.shopsecure.provider.payment.PaymentTransactionStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.util.Collection;
import com.example.shopsecure.entity.PaymentReceiptClassification;

public interface PaymentReceiptRepository extends JpaRepository<PaymentReceipt, Long> {
    boolean existsByProviderEventKey(String providerEventKey);

    Optional<PaymentReceipt> findTopByOrder_IdOrderByCreatedAtDesc(Long orderId);

    boolean existsByOrder_IdAndProviderStatus(Long orderId, PaymentTransactionStatus providerStatus);

    @Query("select coalesce(sum(r.amount), 0) from PaymentReceipt r "
            + "where r.order.id = :orderId and r.classification in :classifications")
    BigDecimal sumAmountByOrderAndClassificationIn(
            @Param("orderId") Long orderId,
            @Param("classifications") Collection<PaymentReceiptClassification> classifications
    );
}
