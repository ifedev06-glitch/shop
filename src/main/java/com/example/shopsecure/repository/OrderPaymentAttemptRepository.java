package com.example.shopsecure.repository;

import com.example.shopsecure.entity.OrderPaymentAttempt;
import com.example.shopsecure.entity.PaymentAttemptStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrderPaymentAttemptRepository extends JpaRepository<OrderPaymentAttempt, Long> {

    Optional<OrderPaymentAttempt> findFirstByOrder_IdAndStatusOrderByCreatedAtDesc(
            Long orderId,
            PaymentAttemptStatus status
    );

    List<OrderPaymentAttempt> findByOrder_IdAndStatus(Long orderId, PaymentAttemptStatus status);

    boolean existsByOrder_Id(Long orderId);

    Optional<OrderPaymentAttempt> findByProviderReference(String reference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from OrderPaymentAttempt a join fetch a.order where a.providerReference = :reference")
    Optional<OrderPaymentAttempt> findByProviderReferenceForUpdate(@Param("reference") String reference);
}
