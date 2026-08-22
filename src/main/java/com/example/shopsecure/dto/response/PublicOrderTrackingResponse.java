package com.example.shopsecure.dto.response;

import com.example.shopsecure.entity.CreatorRole;
import com.example.shopsecure.entity.EscrowReleasePolicy;
import com.example.shopsecure.entity.OrderFundingMode;
import com.example.shopsecure.entity.OrderStatus;
import com.example.shopsecure.entity.PaymentStatus;
import com.example.shopsecure.entity.SettlementStatus;
import com.example.shopsecure.entity.SettlementTrigger;
import com.example.shopsecure.entity.SettlementType;
import com.example.shopsecure.provider.shipping.ShipmentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

public record PublicOrderTrackingResponse(
        Long orderId,
        String itemDescription,
        BigDecimal itemAmount,
        String currency,
        CreatorRole creatorRole,
        OrderFundingMode fundingMode,
        OrderStatus orderStatus,
        Status trackingStatus,
        PaymentStatus paymentStatus,
        String paymentReference,
        Party buyer,
        Party vendor,
        PayoutAccount vendorPayoutAccount,
        PricingBreakdownResponse pricing,
        Parcel parcel,
        Shipment shipment,
        Escrow escrow,
        Instant fundedAt,
        Instant deliveryConfirmedAt,
        Instant carrierPickupConfirmedAt,
        Instant completedAt,
        LocalDateTime createdAt,
        Instant trackingAvailableAt,
        Instant trackingExpiresAt,
        Instant updatedAt
) {
    public record Party(
            CreatorRole role,
            String firstName,
            String lastName,
            String email,
            String phoneNumber,
            AddressResponse address
    ) {
    }

    public record PayoutAccount(
            String bankAccountLast4,
            String bankCode,
            String bankName,
            String bankAccountName
    ) {
    }

    public record Parcel(
            BigDecimal weightKg,
            BigDecimal lengthCm,
            BigDecimal widthCm,
            BigDecimal heightCm,
            int quantity,
            String parcelType
    ) {
    }

    public record Shipment(
            String provider,
            String providerShipmentId,
            ShipmentStatus status,
            String trackingReference,
            String trackingUrl,
            Quote selectedQuote,
            Instant providerUpdatedAt,
            Instant updatedAt
    ) {
    }

    public record Quote(
            Long id,
            String providerRateId,
            String providerInternalId,
            String carrierName,
            String carrierLogo,
            String serviceDescription,
            BigDecimal providerAmount,
            BigDecimal deliveryMarkup,
            BigDecimal deliveryFee,
            BigDecimal serviceFee,
            BigDecimal buyerTotal,
            String currency,
            String pickupEstimate,
            String deliveryEstimate,
            Instant selectedAt,
            Instant expiresAt
    ) {
    }

    public record Escrow(
            EscrowReleasePolicy releasePolicy,
            SettlementStatus status,
            SettlementType settlementType,
            SettlementTrigger trigger,
            BigDecimal amount,
            BigDecimal amountPending,
            BigDecimal amountReleased,
            String currency,
            String payoutReference,
            String transferId,
            String failureReason,
            Instant triggeredAt,
            Instant completedAt
    ) {
    }

    public enum Status {
        BOOKING,
        AWAITING_PICKUP,
        IN_TRANSIT,
        DELIVERED,
        EXCEPTION,
        CANCELLED
    }
}
