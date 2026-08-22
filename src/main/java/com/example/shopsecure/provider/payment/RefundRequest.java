package com.example.shopsecure.provider.payment;

import java.math.BigDecimal;

public record RefundRequest(
        String transactionIdOrReference,
        BigDecimal amount,
        String currency,
        String customerNote,
        String merchantNote
) {
}
