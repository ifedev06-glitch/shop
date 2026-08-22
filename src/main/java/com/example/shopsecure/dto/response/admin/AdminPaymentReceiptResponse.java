package com.example.shopsecure.dto.response.admin;

import com.example.shopsecure.entity.PaymentReceiptClassification;
import com.example.shopsecure.provider.payment.PaymentTransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record AdminPaymentReceiptResponse(
        Long id,
        Long orderId,
        String providerEventKey,
        String providerReference,
        String maskedReceiverAccountNumber,
        BigDecimal amount,
        String currency,
        String channel,
        PaymentTransactionStatus providerStatus,
        PaymentReceiptClassification classification,
        Instant occurredAt,
        Instant createdAt
) {
}
