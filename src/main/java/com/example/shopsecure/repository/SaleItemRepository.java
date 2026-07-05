package com.example.shopsecure.repository;

import com.example.shopsecure.entity.SaleItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SaleItemRepository extends JpaRepository<SaleItem, Long> {

    List<SaleItem> findBySale_Business_IdOrderBySale_CreatedAtDesc(Long businessId);
}
