package com.example.shopsecure.provider.payment;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentStatusUpdatedEvent(
        String reference,
        PaymentTransactionStatus status,
        BigDecimal amount,
        Instant occurredAt,
        boolean relevant
) {
    public static PaymentStatusUpdatedEvent ignored() {
        return new PaymentStatusUpdatedEvent(
                null,
                PaymentTransactionStatus.UNKNOWN,
                null,
                Instant.now(),
                false
        );
    }
}
