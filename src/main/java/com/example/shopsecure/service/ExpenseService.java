package com.example.shopsecure.service;

import com.example.shopsecure.dto.request.ExpenseRequest;
import com.example.shopsecure.dto.response.ExpenseResponse;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ExpenseService {

    private final ExpenseRepository expenseRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;

    public ExpenseResponse createExpense(ExpenseRequest request) {
        User currentUser = getCurrentUser();
        Business business = businessRepository.findByOwner_Id(currentUser.getId())
                .orElseThrow(() -> new BusinessException("You need to create a business first"));

        Expense expense = Expense.builder()
                .title(request.getTitle())
                .description(request.getDescription())
                .amount(request.getAmount())
                .category(request.getCategory())
                .expenseDate(request.getExpenseDate() != null ? request.getExpenseDate() : LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .business(business)
                .build();

        Expense saved = expenseRepository.save(expense);
        log.info("Expense created: id={}, businessId={}, amount={}", saved.getId(), business.getId(), request.getAmount());
        return toResponse(saved);
    }

    public List<ExpenseResponse> getMyExpenses() {
        User currentUser = getCurrentUser();
        Business business = businessRepository.findByOwner_Id(currentUser.getId())
                .orElseThrow(() -> new BusinessException("You need to create a business first"));

        return expenseRepository.findByBusiness_IdOrderByCreatedAtDesc(business.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public ExpenseResponse getExpenseById(Long expenseId) {
        User currentUser = getCurrentUser();
        Business business = businessRepository.findByOwner_Id(currentUser.getId())
                .orElseThrow(() -> new BusinessException("You need to create a business first"));

        Expense expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found"));

        if (!expense.getBusiness().getId().equals(business.getId())) {
            throw new ResourceNotFoundException("Expense not found");
        }

        return toResponse(expense);
    }

    public ExpenseResponse updateExpense(Long expenseId, ExpenseRequest request) {
        User currentUser = getCurrentUser();
        Business business = businessRepository.findByOwner_Id(currentUser.getId())
                .orElseThrow(() -> new BusinessException("You need to create a business first"));

        Expense expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found"));

        if (!expense.getBusiness().getId().equals(business.getId())) {
            throw new ResourceNotFoundException("Expense not found");
        }

        expense.setTitle(request.getTitle());
        expense.setDescription(request.getDescription());
        expense.setAmount(request.getAmount());
        expense.setCategory(request.getCategory());
        expense.setExpenseDate(request.getExpenseDate() != null ? request.getExpenseDate() : expense.getExpenseDate());

        Expense saved = expenseRepository.save(expense);
        log.info("Expense updated: id={}", expenseId);
        return toResponse(saved);
    }

    public void deleteExpense(Long expenseId) {
        User currentUser = getCurrentUser();
        Business business = businessRepository.findByOwner_Id(currentUser.getId())
                .orElseThrow(() -> new BusinessException("You need to create a business first"));

        Expense expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found"));

        if (!expense.getBusiness().getId().equals(business.getId())) {
            throw new ResourceNotFoundException("Expense not found");
        }

        expenseRepository.delete(expense);
        log.info("Expense deleted: id={}", expenseId);
    }

    private User getCurrentUser() {
        String email = SecurityContextHolder.getContext()
                .getAuthentication()
                .getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private ExpenseResponse toResponse(Expense expense) {
        return ExpenseResponse.builder()
                .id(expense.getId())
                .title(expense.getTitle())
                .description(expense.getDescription())
                .amount(expense.getAmount())
                .category(expense.getCategory())
                .expenseDate(expense.getExpenseDate())
                .createdAt(expense.getCreatedAt())
                .build();
    }
}
