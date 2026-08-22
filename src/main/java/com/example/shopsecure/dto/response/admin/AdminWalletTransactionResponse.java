package com.example.shopsecure.dto.response.admin;

import com.example.shopsecure.entity.WalletTransactionType;

import java.math.BigDecimal;
import java.time.Instant;

public record AdminWalletTransactionResponse(
        Long id,
        Long userId,
        String maskedUserEmail,
        Long orderId,
        Long withdrawalId,
        Long paymentReceiptId,
        WalletTransactionType transactionType,
        String reference,
        BigDecimal amount,
        BigDecimal balanceBefore,
        BigDecimal balanceAfter,
        Instant createdAt
) {
}
