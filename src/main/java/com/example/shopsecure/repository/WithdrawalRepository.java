package com.example.shopsecure.repository;

import com.example.shopsecure.entity.Withdrawal;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WithdrawalRepository
        extends JpaRepository<Withdrawal, Long> {

    List<Withdrawal> findByBusiness_IdOrderByCreatedAtDesc(
            Long businessId
    );

    Optional<Withdrawal> findByReference(String reference);

    Optional<Object> findByTransferCode(String transferCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Withdrawal w WHERE w.transferCode = :transferCode")
    Optional<Withdrawal> findByTransferCodeForUpdate(@Param("transferCode") String transferCode);

}