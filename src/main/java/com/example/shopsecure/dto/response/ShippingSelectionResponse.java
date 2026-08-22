package com.example.shopsecure.dto.response;

import com.example.shopsecure.entity.OrderStatus;
import com.example.shopsecure.entity.OrderFundingMode;

public record ShippingSelectionResponse(
        OrderStatus status,
        OrderFundingMode fundingMode,
        ShippingQuoteResponse selectedQuote,
        PricingBreakdownResponse pricing,
        PaymentInstructionsResponse paymentInstructions,
        String buyerPaymentLink
) {
}
