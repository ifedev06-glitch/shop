package com.example.shopsecure.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OrderAddressRequest(
        @NotBlank(message = "Address line 1 is required")
        @Size(max = 255)
        String line1,

        @NotBlank(message = "City is required")
        @Size(max = 100)
        String city,

        @NotBlank(message = "State is required")
        @Size(max = 100)
        String state,

        @NotBlank(message = "Country is required")
        @Size(max = 100)
        String country
) {
}
