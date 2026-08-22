package com.example.shopsecure.dto.response;

import java.math.BigDecimal;

public record WaybillParcelResponse(
        BigDecimal weightKg,
        BigDecimal lengthCm,
        BigDecimal widthCm,
        BigDecimal heightCm,
        int quantity,
        String parcelType
) {
}
