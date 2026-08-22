package com.example.shopsecure.dto.response.admin;

import com.example.shopsecure.entity.CreatorRole;
import com.example.shopsecure.entity.EscrowReleasePolicy;
import com.example.shopsecure.entity.OrderFundingMode;
import com.example.shopsecure.entity.OrderStatus;
import com.example.shopsecure.entity.PaymentStatus;
import com.example.shopsecure.entity.SettlementStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

public record AdminOrderSummaryResponse(
        Long id,
        Long creatorId,
        String creatorName,
        String maskedCreatorEmail,
        String maskedCreatorPhoneNumber,
        String itemDescription,
        CreatorRole creatorRole,
        OrderFundingMode fundingMode,
        OrderStatus status,
        PaymentStatus paymentStatus,
        SettlementStatus settlementStatus,
        EscrowReleasePolicy escrowReleasePolicy,
        BigDecimal itemAmount,
        BigDecimal deliveryFee,
        BigDecimal serviceFee,
        BigDecimal totalAmount,
        String currency,
        Instant fundedAt,
        Instant deliveryConfirmedAt,
        Instant carrierPickupConfirmedAt,
        Instant completedAt,
        LocalDateTime createdAt,
        Instant updatedAt
) {
}
