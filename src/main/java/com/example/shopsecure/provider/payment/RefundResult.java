package com.example.shopsecure.provider.payment;

import java.math.BigDecimal;
import java.time.Instant;

public record RefundResult(
        String refundId,
        String refundReference,
        String transactionId,
        String transactionReference,
        BigDecimal amount,
        String currency,
        RefundStatus status,
        String merchantNote,
        Instant updatedAt
) {
}
