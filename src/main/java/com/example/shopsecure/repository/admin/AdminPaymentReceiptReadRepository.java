package com.example.shopsecure.repository.admin;

import com.example.shopsecure.entity.PaymentReceipt;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

import java.util.List;

public interface AdminPaymentReceiptReadRepository
        extends Repository<PaymentReceipt, Long>, JpaSpecificationExecutor<PaymentReceipt> {

    @Override
    @EntityGraph(attributePaths = "order")
    Page<PaymentReceipt> findAll(Specification<PaymentReceipt> specification, Pageable pageable);

    @EntityGraph(attributePaths = "order")
    List<PaymentReceipt> findByOrder_IdOrderByCreatedAtDesc(Long orderId, Pageable pageable);
}
