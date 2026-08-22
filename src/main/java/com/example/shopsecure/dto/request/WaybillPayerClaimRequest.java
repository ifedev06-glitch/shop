package com.example.shopsecure.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import static com.example.shopsecure.util.NigerianPhoneNumber.INPUT_PATTERN;
import static com.example.shopsecure.util.NigerianPhoneNumber.VALIDATION_MESSAGE;

public record WaybillPayerClaimRequest(
        @NotBlank(message = "First name is required")
        @Size(max = 100)
        String firstName,

        @NotBlank(message = "Last name is required")
        @Size(max = 100)
        String lastName,

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be valid")
        @Size(max = 255)
        String email,

        @NotBlank(message = "Phone number is required")
        @Pattern(regexp = INPUT_PATTERN, message = VALIDATION_MESSAGE)
        String phoneNumber
) {
}
