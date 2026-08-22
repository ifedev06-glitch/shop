package com.example.shopsecure.dto.request;

import com.example.shopsecure.entity.ChowdeckMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateChowdeckControlRequest(
        @NotNull(message = "Mode is required")
        ChowdeckMode mode,

        @NotNull(message = "Expected version is required")
        @PositiveOrZero(message = "Expected version cannot be negative")
        Long expectedVersion,

        @NotBlank(message = "Change reason is required")
        @Size(max = 500, message = "Change reason cannot exceed 500 characters")
        String reason
) {
}
