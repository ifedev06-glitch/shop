package com.example.shopsecure.repository;

import com.example.shopsecure.entity.WalletTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {
    Optional<WalletTransaction> findByOrder_Id(Long orderId);
}
