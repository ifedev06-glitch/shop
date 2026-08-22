package com.example.shopsecure.provider.payment;

import java.math.BigDecimal;

public record PayoutResponse(
        String transferId,
        String reference,
        PayoutStatus status,
        BigDecimal amount,
        String currency,
        String recipientId
) {
    public PayoutResponse(String transferId, String reference, PayoutStatus status) {
        this(transferId, reference, status, null, null, null);
    }
}
