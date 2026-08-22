package com.example.shopsecure.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

public record ShippingQuoteResponse(
        Long quoteId,
        String carrierName,
        String carrierLogo,
        String serviceDescription,
        BigDecimal deliveryFee,
        BigDecimal serviceFee,
        BigDecimal totalAmount,
        String currency,
        String pickupEstimate,
        String deliveryEstimate,
        Instant expiresAt
) {
}
