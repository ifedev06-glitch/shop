package com.example.shopsecure.dto.response;

import com.example.shopsecure.entity.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

public record OrderDetailsResponse(
        Long id,
        String itemDescription,
        BigDecimal itemAmount,
        String currency,
        CreatorRole creatorRole,
        CreatorRole counterpartyRole,
        OrderFundingMode fundingMode,
        OrderStatus status,
        PaymentStatus paymentStatus,
        SettlementStatus settlementStatus,
        PricingBreakdownResponse pricing,
        PaymentInstructionsResponse paymentInstructions,
        CounterpartyResponse counterparty,
        ParcelResponse parcel,
        ShippingStatusResponse shipping,
        Instant fundedAt,
        Instant deliveryConfirmedAt,
        Instant completedAt,
        LocalDateTime createdAt,
        Instant updatedAt
) {
}
