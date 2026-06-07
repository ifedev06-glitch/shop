package com.example.shopsecure.repository;

import com.example.shopsecure.entity.BankAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BankAccountRepository
        extends JpaRepository<BankAccount, Long> {

    Optional<BankAccount> findByBusiness_Id(Long businessId);
}