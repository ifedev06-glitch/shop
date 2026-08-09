package com.example.shopsecure.provider.payment;

import java.math.BigDecimal;
import java.util.Map;

public record PaymentRequest(
        BigDecimal amount,
        String customerEmail,
        String reference,
        String currency,
        String callbackUrl,
        Map<String, Object> metadata
) {
}
