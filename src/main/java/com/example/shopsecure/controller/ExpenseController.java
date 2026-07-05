package com.example.shopsecure.controller;

import com.example.shopsecure.dto.request.ExpenseRequest;
import com.example.shopsecure.dto.response.ExpenseResponse;
import com.example.shopsecure.service.ExpenseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/vendor/expenses")
@RequiredArgsConstructor
@Slf4j
public class ExpenseController {

    private final ExpenseService expenseService;

    @PostMapping
    public ResponseEntity<ExpenseResponse> createExpense(@Valid @RequestBody ExpenseRequest request) {
        log.info("Create expense request: title={}, amount={}", request.getTitle(), request.getAmount());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(expenseService.createExpense(request));
    }

    @GetMapping
    public ResponseEntity<List<ExpenseResponse>> getMyExpenses() {
        log.info("Fetching vendor's expenses");
        return ResponseEntity.ok(expenseService.getMyExpenses());
    }

    @GetMapping("/{expenseId}")
    public ResponseEntity<ExpenseResponse> getExpenseById(@PathVariable Long expenseId) {
        log.info("Fetching expense by id: expenseId={}", expenseId);
        return ResponseEntity.ok(expenseService.getExpenseById(expenseId));
    }

    @PutMapping("/{expenseId}")
    public ResponseEntity<ExpenseResponse> updateExpense(
            @PathVariable Long expenseId,
            @Valid @RequestBody ExpenseRequest request) {
        log.info("Update expense request: expenseId={}", expenseId);
        return ResponseEntity.ok(expenseService.updateExpense(expenseId, request));
    }

    @DeleteMapping("/{expenseId}")
    public ResponseEntity<Void> deleteExpense(@PathVariable Long expenseId) {
        log.info("Delete expense request: expenseId={}", expenseId);
        expenseService.deleteExpense(expenseId);
        return ResponseEntity.noContent().build();
    }
}
