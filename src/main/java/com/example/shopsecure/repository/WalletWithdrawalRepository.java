package com.example.shopsecure.repository;

import com.example.shopsecure.entity.WalletWithdrawal;
import com.example.shopsecure.entity.WalletWithdrawalStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WalletWithdrawalRepository extends JpaRepository<WalletWithdrawal, Long> {
    Optional<WalletWithdrawal> findByReference(String reference);

    Optional<WalletWithdrawal> findByTransferId(String transferId);

    Optional<WalletWithdrawal> findByWallet_IdAndIdempotencyKey(Long walletId, String idempotencyKey);

    Page<WalletWithdrawal> findByWallet_User_Id(Long userId, Pageable pageable);

    List<WalletWithdrawal> findByStatusIn(List<WalletWithdrawalStatus> statuses, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WalletWithdrawal w where w.id = :id")
    Optional<WalletWithdrawal> findByIdForUpdate(@Param("id") Long id);
}
