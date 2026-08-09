package com.example.shopsecure.dto.response;

import java.time.Instant;

public record VirtualAccountDetailsResponse(
        String accountNumber,
        String bankName,
        String accountName,
        Instant expiresAt
) {
}
