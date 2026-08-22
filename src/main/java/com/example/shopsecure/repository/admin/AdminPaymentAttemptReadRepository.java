package com.example.shopsecure.repository.admin;

import com.example.shopsecure.entity.OrderPaymentAttempt;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

import java.util.List;

public interface AdminPaymentAttemptReadRepository
        extends Repository<OrderPaymentAttempt, Long>, JpaSpecificationExecutor<OrderPaymentAttempt> {

    @Override
    @EntityGraph(attributePaths = "order")
    Page<OrderPaymentAttempt> findAll(Specification<OrderPaymentAttempt> specification, Pageable pageable);

    @EntityGraph(attributePaths = "order")
    List<OrderPaymentAttempt> findByOrder_IdOrderByCreatedAtDesc(Long orderId, Pageable pageable);
}
