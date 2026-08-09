package com.example.shopsecure.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @NotBlank(message = "First name is required")
        @Size(max = 100)
        String firstname,

        @NotBlank(message = "Last name is required")
        @Size(max = 100)
        String lastname,

        @NotBlank(message = "Phone number is required")
        @Pattern(regexp = "^\\+?\\d{7,15}$", message = "Phone number must contain 7 to 15 digits")
        String phoneNumber
) {
}
