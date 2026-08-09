package com.example.shopsecure.provider.shipping;

import java.math.BigDecimal;

public record ParcelDetails(
        BigDecimal weightKg,
        BigDecimal lengthCm,
        BigDecimal widthCm,
        BigDecimal heightCm,
        String description,
        String itemName,
        BigDecimal value,
        String currency,
        Integer quantity,
        String type
) {
}
