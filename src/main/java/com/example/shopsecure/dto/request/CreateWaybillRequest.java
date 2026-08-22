package com.example.shopsecure.dto.request;

import com.example.shopsecure.entity.WaybillPartyRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateWaybillRequest(
        @NotNull(message = "Creator role is required")
        WaybillPartyRole creatorRole,

        @NotNull(message = "Payer role is required")
        WaybillPartyRole payerRole,

        @NotBlank(message = "Description is required")
        @Size(max = 2000, message = "Description must not exceed 2000 characters")
        String description,

        @NotNull(message = "Declared value is required")
        @DecimalMin(value = "0.01", message = "Declared value must be greater than zero")
        @Digits(integer = 36, fraction = 2, message = "Declared value must have at most 2 decimal places")
        BigDecimal declaredValue,

        @Valid
        @NotNull(message = "Creator address is required")
        OrderAddressRequest creatorAddress,

        @Valid
        @NotNull(message = "Parcel is required")
        OrderParcelRequest parcel,

        @Valid
        @NotNull(message = "Opposite party is required")
        WaybillPartyRequest oppositeParty
) {
}
