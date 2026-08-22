package com.example.shopsecure.dto.response;

import java.time.LocalDateTime;

public record WaybillQuoteSelectionResponse(
        WaybillQuoteResponse selectedQuote,
        String paymentLink,
        LocalDateTime paymentLinkExpiresAt
) {
}
