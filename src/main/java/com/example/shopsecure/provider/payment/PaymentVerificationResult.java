package com.example.shopsecure.provider.payment;

import java.math.BigDecimal;

public record PaymentVerificationResult(
        PaymentTransactionStatus status,
        BigDecimal amount,
        String reference
) {
}
