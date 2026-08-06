package com.example.shopsecure.provider.payment;

public record BankAccountLookupRequest(
        String accountNumber,
        String bankCode
) {
}
