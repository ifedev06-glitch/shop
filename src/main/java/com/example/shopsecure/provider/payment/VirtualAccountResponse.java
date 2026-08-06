package com.example.shopsecure.provider.payment;

import java.time.Instant;

public record VirtualAccountResponse(
        String accountNumber,
        String bankName,
        String accountName,
        Instant expiresAt
) {
}
