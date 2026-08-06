package com.example.shopsecure.dto.response;

import com.example.shopsecure.entity.CreatorRole;
import com.example.shopsecure.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CreateOrderResponse(
        Long id,
        BigDecimal amount,
        String itemDescription,
        CreatorRole creatorRole,
        OrderStatus status,
        String shareToken,
        String shareableLink,
        LocalDateTime createdAt
) {
}
