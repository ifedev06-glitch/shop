package com.example.shopsecure.repository;

import com.example.shopsecure.entity.Payment;
import com.example.shopsecure.entity.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByReference(String reference);
    Optional<Payment> findByOrder_Id(Long orderId);

    Optional<Payment> findByOrder_IdAndStatus(
            Long orderId, PaymentStatus status
    );
}
