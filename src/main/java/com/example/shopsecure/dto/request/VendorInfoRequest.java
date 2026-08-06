package com.example.shopsecure.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record VendorInfoRequest(
        @NotBlank(message = "Phone number is required")
        @Pattern(regexp = "^\\d{7,15}$", message = "Phone number must be 7-15 digits")
        String phoneNumber,

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be valid")
        @Size(max = 255)
        String email,

        @Valid
        @NotNull(message = "Address is required")
        OrderAddressRequest address,

        @Valid
        @NotNull(message = "Bank account is required")
        VendorBankAccountRequest bankAccount
) {
}
