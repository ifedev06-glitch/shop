package com.example.shopsecure.repository;

import com.example.shopsecure.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    boolean existsByNameAndBusiness_Id(String name, Long businessId);

    List<Category> findByBusiness_Id(Long businessId);

    Optional<Category> findByIdAndBusiness_Id(Long id, Long businessId);
}