package com.example.shopsecure.provider.payment;

import java.math.BigDecimal;
import java.time.Instant;

public record RefundStatusUpdatedEvent(
        String eventKey,
        String refundReference,
        String transactionReference,
        BigDecimal amount,
        String currency,
        RefundStatus status,
        Instant occurredAt,
        boolean relevant
) {
    public static RefundStatusUpdatedEvent ignored() {
        return new RefundStatusUpdatedEvent(
                null,
                null,
                null,
                null,
                null,
                RefundStatus.UNKNOWN,
                Instant.now(),
                false
        );
    }
}
