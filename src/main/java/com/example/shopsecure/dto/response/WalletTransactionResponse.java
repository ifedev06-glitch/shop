package com.example.shopsecure.dto.response;

import com.example.shopsecure.entity.WalletTransactionType;

import java.math.BigDecimal;
import java.time.Instant;

public record WalletTransactionResponse(
        String reference,
        WalletTransactionType type,
        BigDecimal amount,
        BigDecimal balanceBefore,
        BigDecimal balanceAfter,
        Long orderId,
        Instant createdAt
) {
}
