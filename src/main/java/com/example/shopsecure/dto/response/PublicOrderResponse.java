package com.example.shopsecure.dto.response;

import com.example.shopsecure.entity.OrderStatus;

import java.math.BigDecimal;

public record PublicOrderResponse(
        BigDecimal amount,
        String itemDescription,
        String currency,
        OrderStatus status
) {
}
