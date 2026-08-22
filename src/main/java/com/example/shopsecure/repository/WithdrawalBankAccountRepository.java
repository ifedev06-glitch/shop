package com.example.shopsecure.repository;

import com.example.shopsecure.entity.WithdrawalBankAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WithdrawalBankAccountRepository extends JpaRepository<WithdrawalBankAccount, Long> {
    Optional<WithdrawalBankAccount> findByUser_Id(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from WithdrawalBankAccount a where a.user.id = :userId")
    Optional<WithdrawalBankAccount> findByUserIdForUpdate(@Param("userId") Long userId);
}
