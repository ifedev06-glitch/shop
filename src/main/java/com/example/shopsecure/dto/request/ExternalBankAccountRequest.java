package com.example.shopsecure.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ExternalBankAccountRequest(
        @NotBlank(message = "Account number is required")
        @Pattern(regexp = "^\\d{10}$", message = "Account number must be 10 digits")
        String accountNumber,

        @NotBlank(message = "Bank code is required")
        @Size(max = 20)
        String bankCode
) {
}
