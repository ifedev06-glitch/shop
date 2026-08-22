package com.example.shopsecure.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ResendWaybillOtpRequest(
        @NotBlank(message = "Challenge ID is required")
        String challengeId
) {
}
