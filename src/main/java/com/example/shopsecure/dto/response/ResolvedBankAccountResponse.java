package com.example.shopsecure.dto.response;

public record ResolvedBankAccountResponse(
        String accountNumberMasked,
        String accountName,
        String bankCode,
        String bankName
) {
}
