package com.example.shopsecure.dto.request;

import jakarta.validation.constraints.AssertTrue;

public record ConfirmDeliveryRequest(
        @AssertTrue(message = "Delivery confirmation is required")
        boolean confirmation
) {
}
