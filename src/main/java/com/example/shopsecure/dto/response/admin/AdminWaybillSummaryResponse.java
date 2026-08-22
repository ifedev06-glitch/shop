package com.example.shopsecure.dto.response.admin;

import com.example.shopsecure.entity.WaybillPartyRole;
import com.example.shopsecure.entity.WaybillPayerType;
import com.example.shopsecure.entity.WaybillPaymentStatus;
import com.example.shopsecure.entity.WaybillStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AdminWaybillSummaryResponse(
        Long id,
        String publicId,
        Long creatorId,
        String creatorName,
        String maskedCreatorEmail,
        String maskedCreatorPhoneNumber,
        WaybillPartyRole creatorRole,
        WaybillPayerType payerType,
        String description,
        BigDecimal declaredValue,
        String currency,
        WaybillStatus status,
        WaybillPaymentStatus paymentStatus,
        BigDecimal providerDeliveryFee,
        BigDecimal deliveryMarkup,
        BigDecimal deliveryFee,
        BigDecimal serviceFee,
        BigDecimal totalAmount,
        LocalDateTime paymentDueAt,
        String fundingPaymentReference,
        LocalDateTime fundedAt,
        LocalDateTime completedAt,
        String cancellationReason,
        LocalDateTime cancellationRequestedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
