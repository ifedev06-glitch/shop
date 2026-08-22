package com.example.shopsecure.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ChowdeckWebhookRequest(
        @NotNull(message = "Webhook payload is required")
        @Valid
        Payload payload
) {
    public record Payload(
            @NotBlank(message = "Delivery reference is required")
            @Size(max = 100, message = "Delivery reference cannot exceed 100 characters")
            @Pattern(
                    regexp = "[A-Za-z0-9][A-Za-z0-9_-]*",
                    message = "Delivery reference is invalid"
            )
            String reference
    ) {
    }
}
