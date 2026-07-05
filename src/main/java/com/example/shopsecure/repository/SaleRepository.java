package com.example.shopsecure.repository;

import com.example.shopsecure.entity.Sale;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface SaleRepository extends JpaRepository<Sale, Long> {

    List<Sale> findByBusiness_IdOrderByCreatedAtDesc(Long businessId);

    Page<Sale> findByBusiness_IdOrderByCreatedAtDesc(Long businessId, Pageable pageable);

    List<Sale> findByBusiness_IdAndCreatedAtBetweenOrderByCreatedAtDesc(
            Long businessId, LocalDateTime start, LocalDateTime end);
}
