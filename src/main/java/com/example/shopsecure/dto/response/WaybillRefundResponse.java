package com.example.shopsecure.dto.response;

import com.example.shopsecure.entity.WaybillRefundStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record WaybillRefundResponse(
        Long refundId,
        String providerRefundId,
        String providerRefundReference,
        BigDecimal amount,
        String currency,
        WaybillRefundStatus status,
        String reason,
        String failureReason,
        LocalDateTime providerCreatedAt,
        LocalDateTime providerUpdatedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime completedAt
) {
}
