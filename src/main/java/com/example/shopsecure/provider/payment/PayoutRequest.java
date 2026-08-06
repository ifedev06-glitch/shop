package com.example.shopsecure.provider.payment;

import java.math.BigDecimal;

public record PayoutRequest(
        String recipientId,
        BigDecimal amount,
        String reference,
        String description
) {
}
