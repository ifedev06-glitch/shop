package com.example.shopsecure.repository.admin;

import com.example.shopsecure.entity.WalletWithdrawal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

public interface AdminWithdrawalReadRepository
        extends Repository<WalletWithdrawal, Long>, JpaSpecificationExecutor<WalletWithdrawal> {

    @Override
    @EntityGraph(attributePaths = {"wallet", "wallet.user"})
    Page<WalletWithdrawal> findAll(Specification<WalletWithdrawal> specification, Pageable pageable);
}
