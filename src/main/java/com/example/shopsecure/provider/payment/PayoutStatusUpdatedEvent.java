package com.example.shopsecure.provider.payment;

import java.math.BigDecimal;
import java.time.Instant;

public record PayoutStatusUpdatedEvent(
        String reference,
        String transferId,
        PayoutStatus status,
        BigDecimal amount,
        String currency,
        String recipientId,
        Instant occurredAt,
        boolean relevant
) {
    public PayoutStatusUpdatedEvent(
            String reference,
            String transferId,
            PayoutStatus status,
            Instant occurredAt,
            boolean relevant
    ) {
        this(reference, transferId, status, null, null, null, occurredAt, relevant);
    }

    public static PayoutStatusUpdatedEvent ignored() {
        return new PayoutStatusUpdatedEvent(
                null,
                null,
                PayoutStatus.UNKNOWN,
                null,
                null,
                null,
                Instant.now(),
                false
        );
    }
}
