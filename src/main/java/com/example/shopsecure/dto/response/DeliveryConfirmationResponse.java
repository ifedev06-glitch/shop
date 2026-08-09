package com.example.shopsecure.dto.response;

import com.example.shopsecure.entity.OrderStatus;
import com.example.shopsecure.entity.SettlementStatus;

import java.time.Instant;

public record DeliveryConfirmationResponse(
        OrderStatus status,
        SettlementStatus settlementStatus,
        Instant deliveryConfirmedAt,
        Instant completedAt
) {
}
