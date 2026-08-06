package com.example.shopsecure.provider.payment;

import java.time.Instant;

public record PayoutStatusUpdatedEvent(
        String reference,
        String transferId,
        PayoutStatus status,
        Instant occurredAt,
        boolean relevant
) {
    public static PayoutStatusUpdatedEvent ignored() {
        return new PayoutStatusUpdatedEvent(null, null, PayoutStatus.UNKNOWN, Instant.now(), false);
    }
}
