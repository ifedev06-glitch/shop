package com.example.shopsecure.dto.response;

import java.math.BigDecimal;

public record PaymentInstructionsResponse(
        VirtualAccountDetailsResponse virtualAccount,
        BigDecimal amount,
        String currency,
        String paymentReference,
        String narration
) {
}
