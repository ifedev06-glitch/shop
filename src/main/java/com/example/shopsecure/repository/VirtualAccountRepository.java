package com.example.shopsecure.repository;

import com.example.shopsecure.entity.VirtualAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface VirtualAccountRepository extends JpaRepository<VirtualAccount, Long> {
    Optional<VirtualAccount> findByUser_Id(Long userId);

    Optional<VirtualAccount> findByAccountNumber(String accountNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from VirtualAccount v join fetch v.user where v.accountNumber = :accountNumber")
    Optional<VirtualAccount> findByAccountNumberForUpdate(@Param("accountNumber") String accountNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from VirtualAccount v where v.user.id = :userId")
    Optional<VirtualAccount> findByUserIdForUpdate(@Param("userId") Long userId);
}
