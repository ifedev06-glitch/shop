package com.example.shopsecure.dto.response;

import com.example.shopsecure.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record WalletPaymentResponse(
        Long orderId,
        OrderStatus status,
        BigDecimal amount,
        BigDecimal availableBalance,
        BigDecimal processingBalance,
        String currency,
        Instant paidAt
) {
}
