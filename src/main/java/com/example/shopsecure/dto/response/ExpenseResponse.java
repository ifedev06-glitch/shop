package com.example.shopsecure.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor
public class ExpenseResponse {

    private Long id;
    private String title;
    private String description;
    private BigDecimal amount;
    private String category;
    private LocalDateTime expenseDate;
    private LocalDateTime createdAt;
}
