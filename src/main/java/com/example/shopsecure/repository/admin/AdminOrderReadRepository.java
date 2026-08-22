package com.example.shopsecure.repository.admin;

import com.example.shopsecure.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

import java.util.Optional;

public interface AdminOrderReadRepository extends Repository<Order, Long>, JpaSpecificationExecutor<Order> {
    @Override
    @EntityGraph(attributePaths = "creator")
    Page<Order> findAll(Specification<Order> specification, Pageable pageable);

    @EntityGraph(attributePaths = "creator")
    Optional<Order> findById(Long id);
}
