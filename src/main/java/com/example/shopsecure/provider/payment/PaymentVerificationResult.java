package com.example.shopsecure.provider.payment;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentVerificationResult(
        String eventId,
        String reference,
        PaymentTransactionStatus status,
        BigDecimal amount,
        String currency,
        String channel,
        Instant occurredAt
) {
}
