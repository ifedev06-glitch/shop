package com.example.shopsecure.repository;

import com.example.shopsecure.entity.WalletTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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

    Optional<WalletTransaction> findByWithdrawal_IdAndTransactionType(
            Long withdrawalId,
            WalletTransactionType transactionType
    );

    Page<WalletTransaction> findByWallet_User_Id(Long userId, Pageable pageable);
}
