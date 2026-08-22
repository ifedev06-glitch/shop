package com.example.shopsecure.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record WalletWithdrawalChallengeRequest(
        @NotNull(message = "Withdrawal amount is required")
        @DecimalMin(value = "0.01", message = "Withdrawal amount must be greater than zero")
        @Digits(integer = 12, fraction = 2, message = "Withdrawal amount must have at most 2 decimal places")
        BigDecimal amount
) {
}
