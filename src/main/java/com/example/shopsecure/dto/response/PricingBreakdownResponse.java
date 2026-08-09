package com.example.shopsecure.dto.response;

import java.math.BigDecimal;

public record PricingBreakdownResponse(
        BigDecimal itemAmount,
        BigDecimal deliveryFee,
        BigDecimal serviceFee,
        BigDecimal totalAmount,
        String currency
) {
}
