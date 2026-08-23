package com.example.shopsecure.repository;

import com.example.shopsecure.entity.OrderDispute;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface OrderDisputeRepository
        extends JpaRepository<OrderDispute, Long>, JpaSpecificationExecutor<OrderDispute> {

    boolean existsByOrder_Id(Long orderId);

    Optional<OrderDispute> findByOrder_Id(Long orderId);

    @Override
    @EntityGraph(attributePaths = "order")
    Page<OrderDispute> findAll(Specification<OrderDispute> specification, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = "order")
    Optional<OrderDispute> findById(Long id);
}
