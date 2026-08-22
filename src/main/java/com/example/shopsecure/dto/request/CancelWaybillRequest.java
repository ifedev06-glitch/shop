package com.example.shopsecure.dto.request;

import jakarta.validation.constraints.Size;

public record CancelWaybillRequest(
        @Size(max = 500, message = "Cancellation reason must not exceed 500 characters")
        String reason
) {
}
