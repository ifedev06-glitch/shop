package com.example.shopsecure.dto.response;

import com.example.shopsecure.entity.WaybillPartyRole;
import com.example.shopsecure.entity.WaybillPaymentAttemptStatus;
import com.example.shopsecure.entity.WaybillPaymentStatus;
import com.example.shopsecure.entity.WaybillStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record WaybillCheckoutResponse(
        String waybillPublicId,
        Long paymentAttemptId,
        WaybillPartyRole payerRole,
        WaybillStatus status,
        WaybillPaymentStatus paymentStatus,
        WaybillPaymentAttemptStatus attemptStatus,
        BigDecimal amount,
        String currency,
        String reference,
        String authorizationUrl,
        String accessCode,
        LocalDateTime createdAt
) {
}
