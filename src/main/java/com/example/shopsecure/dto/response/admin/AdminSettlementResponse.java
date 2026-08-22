package com.example.shopsecure.dto.response.admin;

import com.example.shopsecure.entity.SettlementStatus;
import com.example.shopsecure.entity.SettlementTrigger;
import com.example.shopsecure.entity.SettlementType;

import java.math.BigDecimal;
import java.time.Instant;

public record AdminSettlementResponse(
        Long id,
        Long orderId,
        SettlementType settlementType,
        BigDecimal amount,
        String currency,
        SettlementStatus status,
        SettlementTrigger trigger,
        Instant triggeredAt,
        String payoutReference,
        String transferId,
        String unexpectedPayoutReference,
        String unexpectedTransferId,
        int payoutAttempt,
        String failureReason,
        Instant providerUpdatedAt,
        Instant nextReconciliationAt,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
) {
}
