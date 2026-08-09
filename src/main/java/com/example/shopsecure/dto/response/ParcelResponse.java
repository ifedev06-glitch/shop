package com.example.shopsecure.dto.response;

import java.math.BigDecimal;

public record ParcelResponse(
        BigDecimal weightKg,
        BigDecimal lengthCm,
        BigDecimal widthCm,
        BigDecimal heightCm
) {
}
