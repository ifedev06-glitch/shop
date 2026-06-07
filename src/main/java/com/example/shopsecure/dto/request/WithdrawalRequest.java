package com.example.shopsecure.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Getter
@Setter
public class WithdrawalRequest {

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "100.00", message = "Minimum withdrawal is ₦100")
    private BigDecimal amount;

    @NotNull(message = "Bank account is required")
    private Long bankAccountId;

    @NotBlank
    private String pin;
}