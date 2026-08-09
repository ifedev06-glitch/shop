package com.example.shopsecure.dto.response;

import com.example.shopsecure.entity.OrderStatus;
import com.example.shopsecure.entity.PaymentStatus;
import com.example.shopsecure.entity.WalletPaymentOutcome;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderWalletPaymentResponse(
        Long orderId,
        WalletPaymentOutcome outcome,
        OrderStatus status,
        PaymentStatus paymentStatus,
        BigDecimal amountRequired,
        BigDecimal walletBalance,
        BigDecimal shortfall,
        String currency,
        PaymentInstructionsResponse topUpInstructions,
        Instant fundedAt
) {
}
