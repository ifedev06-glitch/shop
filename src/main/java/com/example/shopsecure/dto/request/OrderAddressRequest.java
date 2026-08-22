package com.example.shopsecure.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

public record OrderAddressRequest(
        @NotBlank(message = "Address line 1 is required")
        @Size(max = 255)
        String line1,

        @Size(max = 255)
        String line2,

        @NotBlank(message = "City is required")
        @Size(max = 100)
        String city,

        @NotBlank(message = "State is required")
        @Size(max = 100)
        String state,

        @NotBlank(message = "Country is required")
        @Pattern(regexp = "(?i)^NG$", message = "Only Nigerian shipping addresses are supported")
        String country,

        @NotBlank(message = "Postal code is required")
        @Size(max = 32)
        String postalCode
) {
}
