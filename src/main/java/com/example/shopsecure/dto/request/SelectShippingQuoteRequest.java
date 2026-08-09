package com.example.shopsecure.dto.request;

import jakarta.validation.constraints.NotNull;

public record SelectShippingQuoteRequest(
        @NotNull(message = "Quote ID is required")
        Long quoteId
) {
}
