package com.example.shopsecure.provider.payment;

public record PayoutRecipientRequest(
        String accountName,
        String accountNumber,
        String bankCode
) {
}
