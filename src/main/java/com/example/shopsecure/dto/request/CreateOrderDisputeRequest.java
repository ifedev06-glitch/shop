package com.example.shopsecure.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateOrderDisputeRequest(
        @NotBlank(message = "Dispute reason is required")
        @Size(max = 2000, message = "Dispute reason must not exceed 2000 characters")
        @Pattern(
                regexp = "^[\\P{Cc}\\r\\n\\t]+$",
                message = "Dispute reason contains unsupported control characters"
        )
        String reason
) {
}
