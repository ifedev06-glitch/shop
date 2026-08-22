package com.example.shopsecure.dto.response;

import com.example.shopsecure.entity.*;

import java.math.BigDecimal;
import java.time.Instant;

public record GuestOrderStatusResponse(
        OrderStatus status,
        OrderFundingMode fundingMode,
        PaymentStatus paymentStatus,
        SettlementStatus settlementStatus,
        EscrowReleasePolicy escrowReleasePolicy,
        BigDecimal amountExpected,
        BigDecimal amountReceived,
        String currency,
        PaymentInstructionsResponse paymentInstructions,
        ShippingStatusResponse shipping,
        Instant fundedAt,
        Instant deliveryConfirmedAt,
        Instant carrierPickupConfirmedAt,
        Instant completedAt
) {
}
