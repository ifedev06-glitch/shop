package com.example.shopsecure.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

public record SellerPendingAmountResponse(
        BigDecimal pendingAmount,
        BigDecimal fundedPendingAmount,
        BigDecimal awaitingFundingAmount,
        long activeOrderCount,
        long fundedOrderCount,
        String currency,
        Instant asOf
) {
}
