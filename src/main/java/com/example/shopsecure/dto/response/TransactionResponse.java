package com.example.shopsecure.dto.response;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class TransactionResponse {
    private String reference;
    private BigDecimal amount;
    private String type;
    private String status;
    private String description;
    private LocalDateTime createdAt;
}