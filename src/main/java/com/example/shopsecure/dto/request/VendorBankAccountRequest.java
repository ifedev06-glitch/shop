package com.example.shopsecure.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record VendorBankAccountRequest(
        @NotBlank(message = "Account number is required")
        @Pattern(regexp = "^\\d{6,20}$", message = "Account number must be 6-20 digits")
        String accountNumber,

        @NotBlank(message = "Bank name is required")
        @Size(max = 255)
        String bankName
) {
}
