package com.example.shopsecure.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CounterpartyInfoRequest(
        @NotBlank(message = "First name is required")
        @Size(max = 100)
        String firstName,

        @NotBlank(message = "Last name is required")
        @Size(max = 100)
        String lastName,

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be valid")
        String email,

        @NotBlank(message = "Phone number is required")
        @Pattern(regexp = "^\\+?\\d{7,15}$", message = "Phone number must contain 7 to 15 digits")
        String phoneNumber,

        @Valid
        @NotNull(message = "Counterparty address is required")
        OrderAddressRequest address,

        @Valid
        ExternalBankAccountRequest externalBankAccount,

        @Valid
        OrderParcelRequest parcel
) {
}
