package com.example.shopsecure.repository.admin;

import com.example.shopsecure.entity.WalletTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

public interface AdminWalletTransactionReadRepository
        extends Repository<WalletTransaction, Long>, JpaSpecificationExecutor<WalletTransaction> {

    @Override
    @EntityGraph(attributePaths = {"wallet", "wallet.user", "order", "withdrawal", "paymentReceipt"})
    Page<WalletTransaction> findAll(Specification<WalletTransaction> specification, Pageable pageable);
}
