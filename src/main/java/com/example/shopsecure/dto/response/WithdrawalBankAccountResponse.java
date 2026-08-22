package com.example.shopsecure.dto.response;

import java.time.Instant;

public record WithdrawalBankAccountResponse(
        String accountNumberMasked,
        String accountName,
        String bankCode,
        String bankName,
        Instant updatedAt
) {
}
