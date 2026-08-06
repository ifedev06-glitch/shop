package com.example.shopsecure.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;

import java.math.BigDecimal;

public record CreateOrderRequest(
        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
        @Digits(integer = 16, fraction = 2, message = "Amount must have at most 2 decimal places")
        BigDecimal amount,

        @NotBlank(message = "Item description is required")
        @Size(max = 2000, message = "Item description must not exceed 2000 characters")
        String itemDescription,

        @NotBlank(message = "Buyer phone number is required")
        @Pattern(regexp = "^\\d{7,15}$", message = "Phone number must be 7-15 digits")
        String buyerPhoneNumber,

        @NotBlank(message = "Buyer email is required")
        @Email(message = "Buyer email must be valid")
        @Size(max = 255)
        String buyerEmail,

        @Valid
        @NotNull(message = "Delivery address is required")
        OrderAddressRequest deliveryAddress
) {
}
