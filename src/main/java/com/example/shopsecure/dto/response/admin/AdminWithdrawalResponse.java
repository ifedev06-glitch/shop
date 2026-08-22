package com.example.shopsecure.dto.response.admin;

import com.example.shopsecure.entity.WalletWithdrawalStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record AdminWithdrawalResponse(
        Long id,
        Long userId,
        String maskedUserEmail,
        BigDecimal amount,
        String currency,
        WalletWithdrawalStatus status,
        String reference,
        String transferId,
        String maskedAccountNumber,
        String bankName,
        String accountName,
        int dispatchAttempt,
        String failureReason,
        Instant providerUpdatedAt,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
) {
}
