package com.example.shopsecure.dto.response;

import com.example.shopsecure.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

public record OrderDetailsResponse(
        Long id,
        String itemDescription,
        BigDecimal amount,
        String currency,
        String buyerPhoneNumber,
        String buyerEmail,
        String deliveryAddressLine1,
        String deliveryCity,
        String deliveryState,
        String deliveryCountry,
        OrderStatus status,
        Instant paidAt,
        LocalDateTime createdAt,
        Instant updatedAt
) {
}
