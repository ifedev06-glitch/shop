package com.example.shopsecure.dto.response;

import com.example.shopsecure.entity.OrderStatus;

import java.time.Instant;

public record OrderStatusHistoryResponse(
        OrderStatus fromStatus,
        OrderStatus toStatus,
        String reason,
        Instant createdAt
) {
}
