package com.example.shopsecure.dto.response;

import com.example.shopsecure.entity.ShippingQuoteStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record WaybillQuoteResponse(
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
        ShippingQuoteStatus status,
        LocalDateTime expiresAt
) {
}
