package com.example.shopsecure.provider.payment;

import java.math.BigDecimal;

public record PaymentRequest(
        BigDecimal amount,
        String customerEmail,
        String reference
) {
}
