package com.example.shopsecure.dto.response;

import java.time.LocalDateTime;

public record CreateWaybillResponse(
        WaybillDetailsResponse details,
        String paymentLink,
        LocalDateTime expiresAt,
        boolean replayed
) {
}
