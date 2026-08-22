package com.example.shopsecure.dto.response.admin;

import com.example.shopsecure.dto.response.OrderStatusHistoryResponse;
import com.example.shopsecure.entity.ShippingQuoteStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AdminOrderDetailResponse(
        AdminOrderSummaryResponse order,
        PartySnapshot creator,
        PartySnapshot counterparty,
        ParcelSnapshot parcel,
        AdminShipmentResponse shipment,
        ShippingQuoteSnapshot selectedShippingQuote,
        AdminSettlementResponse settlement,
        List<AdminPaymentAttemptResponse> paymentAttempts,
        List<AdminPaymentReceiptResponse> paymentReceipts,
        List<OrderStatusHistoryResponse> history
) {
    public record PartySnapshot(
            String firstName,
            String lastName,
            String maskedEmail,
            String maskedPhoneNumber,
            String city,
            String state,
            String country,
            boolean verified,
            String maskedBankAccountNumber,
            String bankName,
            String bankAccountName
    ) {
    }

    public record ParcelSnapshot(
            BigDecimal weightKg,
            BigDecimal lengthCm,
            BigDecimal widthCm,
            BigDecimal heightCm,
            int quantity,
            String parcelType
    ) {
    }

    public record ShippingQuoteSnapshot(
            Long id,
            String carrierName,
            String serviceDescription,
            BigDecimal providerAmount,
            BigDecimal deliveryMarkup,
            BigDecimal deliveryFee,
            BigDecimal serviceFee,
            BigDecimal buyerTotal,
            String currency,
            String pickupEstimate,
            String deliveryEstimate,
            ShippingQuoteStatus status,
            Instant selectedAt,
            Instant expiresAt
    ) {
    }
}
