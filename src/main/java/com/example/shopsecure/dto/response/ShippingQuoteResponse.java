package com.example.shopsecure.dto.response;

import java.math.BigDecimal;

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
        String deliveryEstimate
) {
}
