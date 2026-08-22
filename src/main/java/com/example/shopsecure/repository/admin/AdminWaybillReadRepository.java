package com.example.shopsecure.repository.admin;

import com.example.shopsecure.entity.Waybill;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

import java.util.Optional;

public interface AdminWaybillReadRepository
        extends Repository<Waybill, Long>, JpaSpecificationExecutor<Waybill> {

    @Override
    @EntityGraph(attributePaths = "creator")
    Page<Waybill> findAll(Specification<Waybill> specification, Pageable pageable);

    @EntityGraph(attributePaths = "creator")
    Optional<Waybill> findById(Long id);
}
