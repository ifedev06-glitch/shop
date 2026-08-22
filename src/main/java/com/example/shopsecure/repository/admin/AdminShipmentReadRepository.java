package com.example.shopsecure.repository.admin;

import com.example.shopsecure.entity.OrderShipment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

public interface AdminShipmentReadRepository
        extends Repository<OrderShipment, Long>, JpaSpecificationExecutor<OrderShipment> {

    @Override
    @EntityGraph(attributePaths = "order")
    Page<OrderShipment> findAll(Specification<OrderShipment> specification, Pageable pageable);
}
