package com.example.shopsecure.repository;

import com.example.shopsecure.entity.Expense;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ExpenseRepository extends JpaRepository<Expense, Long> {

    List<Expense> findByBusiness_IdOrderByCreatedAtDesc(Long businessId);

    List<Expense> findByBusiness_IdAndCreatedAtBetweenOrderByCreatedAtDesc(
            Long businessId, LocalDateTime start, LocalDateTime end);

    List<Expense> findByBusiness_IdAndCategoryOrderByCreatedAtDesc(
            Long businessId, String category);
}
