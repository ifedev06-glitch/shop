package com.example.shopsecure.dto.response.admin;

import com.example.shopsecure.entity.PaymentAttemptStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record AdminPaymentAttemptResponse(
        Long id,
        Long orderId,
        String provider,
        String providerReference,
        BigDecimal amount,
        String currency,
        PaymentAttemptStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
) {
}
