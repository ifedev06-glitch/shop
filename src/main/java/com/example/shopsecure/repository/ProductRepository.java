package com.example.shopsecure.repository;

import com.example.shopsecure.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findByBusiness_Id(Long businessId);

    Optional<Product> findByIdAndBusiness_Id(Long id, Long businessId);

    List<Product> findByBusiness_IdAndCategory_Id(Long businessId, Long categoryId);
}