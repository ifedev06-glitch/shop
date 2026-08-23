package com.example.shopsecure.dto.response.admin;

import com.example.shopsecure.entity.OrderDisputeStatus;
import com.example.shopsecure.entity.OrderStatus;
import com.example.shopsecure.entity.PaymentStatus;
import com.example.shopsecure.entity.SettlementStatus;

import java.time.Instant;

public record AdminOrderDisputeSummaryResponse(
        Long id,
        Long orderId,
        OrderDisputeStatus status,
        OrderStatus orderStatus,
        PaymentStatus paymentStatus,
        SettlementStatus settlementStatus,
        String reasonPreview,
        Instant createdAt,
        Instant updatedAt
) {
}
