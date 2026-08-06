package com.example.shopsecure.provider.payment;

public record ResolvedBankAccount(
        String accountName,
        String accountNumber,
        String bankCode,
        String bankName
) {
}
