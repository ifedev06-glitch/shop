package com.example.shopsecure.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record OrderParcelRequest(
        @NotNull(message = "Parcel weight is required")
        @DecimalMin(value = "0.001", message = "Parcel weight must be greater than zero")
        @Digits(integer = 9, fraction = 3)
        BigDecimal weightKg,

        @DecimalMin(value = "0.01", message = "Parcel length must be greater than zero")
        @Digits(integer = 9, fraction = 2)
        BigDecimal lengthCm,

        @DecimalMin(value = "0.01", message = "Parcel width must be greater than zero")
        @Digits(integer = 9, fraction = 2)
        BigDecimal widthCm,

        @DecimalMin(value = "0.01", message = "Parcel height must be greater than zero")
        @Digits(integer = 9, fraction = 2)
        BigDecimal heightCm
) {
}
