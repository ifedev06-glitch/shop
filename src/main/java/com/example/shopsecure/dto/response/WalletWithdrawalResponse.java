package com.example.shopsecure.dto.response;

import com.example.shopsecure.entity.WalletWithdrawalStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record WalletWithdrawalResponse(
        Long withdrawalId,
        String reference,
        BigDecimal amount,
        String currency,
        WalletWithdrawalStatus status,
        String accountNumberMasked,
        String accountName,
        String bankName,
        String failureReason,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
) {
}
