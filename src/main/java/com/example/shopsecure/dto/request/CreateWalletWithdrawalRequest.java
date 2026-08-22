package com.example.shopsecure.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public record CreateWalletWithdrawalRequest(
        @NotNull(message = "Withdrawal amount is required")
        @DecimalMin(value = "0.01", message = "Withdrawal amount must be greater than zero")
        @Digits(integer = 12, fraction = 2, message = "Withdrawal amount must have at most 2 decimal places")
        BigDecimal amount,

        @NotBlank(message = "Withdrawal challenge ID is required")
        String challengeId,

        @NotBlank(message = "Withdrawal verification code is required")
        @Pattern(regexp = "^\\d{6}$", message = "Withdrawal verification code must be 6 digits")
        String code
) {
}
