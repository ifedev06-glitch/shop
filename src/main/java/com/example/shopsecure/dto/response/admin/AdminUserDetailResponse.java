package com.example.shopsecure.dto.response.admin;

import java.time.Instant;

public record AdminUserDetailResponse(
        AdminUserSummaryResponse user,
        VirtualAccountSnapshot virtualAccount,
        BankAccountSnapshot withdrawalBankAccount,
        long orderCount,
        long withdrawalCount
) {
    public record VirtualAccountSnapshot(
            String maskedAccountNumber,
            String bankName,
            String accountName,
            Instant expiresAt
    ) {
    }

    public record BankAccountSnapshot(
            String maskedAccountNumber,
            String bankCode,
            String bankName,
            String accountName,
            Instant updatedAt
    ) {
    }
}
