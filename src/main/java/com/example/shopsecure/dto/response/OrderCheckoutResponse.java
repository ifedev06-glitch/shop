package com.example.shopsecure.dto.response;

import com.example.shopsecure.entity.OrderFundingMode;
import com.example.shopsecure.entity.OrderStatus;
import com.example.shopsecure.entity.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderCheckoutResponse(
        Long orderId,
        OrderFundingMode fundingMode,
        OrderStatus status,
        PaymentStatus paymentStatus,
        BigDecimal amount,
        String currency,
        String reference,
        String authorizationUrl,
        String accessCode,
        Instant initializedAt
) {
}
