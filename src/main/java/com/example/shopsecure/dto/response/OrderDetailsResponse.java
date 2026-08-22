package com.example.shopsecure.dto.response;

import com.example.shopsecure.entity.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

public record OrderDetailsResponse(
        Long id,
        String buyerPaymentLink,
        String itemDescription,
        BigDecimal itemAmount,
        String currency,
        CreatorRole creatorRole,
        CreatorRole counterpartyRole,
        OrderFundingMode fundingMode,
        OrderStatus status,
        PaymentStatus paymentStatus,
        SettlementStatus settlementStatus,
        EscrowReleasePolicy escrowReleasePolicy,
        PricingBreakdownResponse pricing,
        PaymentInstructionsResponse paymentInstructions,
        CounterpartyResponse counterparty,
        VendorPayoutAccountResponse vendorPayoutAccount,
        ParcelResponse parcel,
        ShippingStatusResponse shipping,
        Instant fundedAt,
        Instant deliveryConfirmedAt,
        Instant carrierPickupConfirmedAt,
        Instant completedAt,
        LocalDateTime createdAt,
        Instant updatedAt
) {
}
