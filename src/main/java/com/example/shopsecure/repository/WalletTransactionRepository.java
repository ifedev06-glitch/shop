package com.example.shopsecure.repository;

import com.example.shopsecure.entity.WalletTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;
import com.example.shopsecure.entity.WalletTransactionType;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {
    Optional<WalletTransaction> findByOrder_IdAndTransactionType(
            Long orderId,
            WalletTransactionType transactionType
    );

    Optional<WalletTransaction> findByReference(String reference);

    Optional<WalletTransaction> findByPaymentReceipt_Id(Long paymentReceiptId);

    List<WalletTransaction> findByWallet_User_IdOrderByCreatedAtDesc(Long userId);
}
