package com.example.shopsecure.provider.payment;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentStatusUpdatedEvent(
        String eventId,
        String reference,
        PaymentTransactionStatus status,
        BigDecimal amount,
        String currency,
        String channel,
        String receiverAccountNumber,
        Instant occurredAt,
        boolean relevant
) {
    public static PaymentStatusUpdatedEvent ignored() {
        return new PaymentStatusUpdatedEvent(
                null,
                null,
                PaymentTransactionStatus.UNKNOWN,
                null,
                null,
                null,
                null,
                Instant.now(),
                false
        );
    }
}
