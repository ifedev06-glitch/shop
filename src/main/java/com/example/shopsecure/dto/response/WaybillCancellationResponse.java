package com.example.shopsecure.dto.response;

import com.example.shopsecure.entity.WaybillPaymentStatus;
import com.example.shopsecure.entity.WaybillStatus;

import java.time.LocalDateTime;

public record WaybillCancellationResponse(
        String waybillPublicId,
        WaybillStatus status,
        WaybillPaymentStatus paymentStatus,
        String reason,
        LocalDateTime cancellationRequestedAt,
        WaybillRefundResponse refund
) {
}
