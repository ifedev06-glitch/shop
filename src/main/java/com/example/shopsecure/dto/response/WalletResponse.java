package com.example.shopsecure.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

public record WalletResponse(
        BigDecimal balance,
        String currency,
        Instant updatedAt
) {
}
