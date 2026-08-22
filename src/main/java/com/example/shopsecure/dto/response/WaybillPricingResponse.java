package com.example.shopsecure.dto.response;

import java.math.BigDecimal;

public record WaybillPricingResponse(
        BigDecimal deliveryFee,
        BigDecimal serviceFee,
        BigDecimal totalAmount,
        String currency
) {
}
