package com.example.shopsecure.repository.admin;

import com.example.shopsecure.entity.OrderSettlement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

public interface AdminSettlementReadRepository
        extends Repository<OrderSettlement, Long>, JpaSpecificationExecutor<OrderSettlement> {

    @Override
    @EntityGraph(attributePaths = "order")
    Page<OrderSettlement> findAll(Specification<OrderSettlement> specification, Pageable pageable);
}
