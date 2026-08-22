package com.example.shopsecure.dto.response;

import com.example.shopsecure.entity.WaybillPartyRole;
import com.example.shopsecure.entity.WaybillPaymentStatus;
import com.example.shopsecure.entity.WaybillStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record WaybillDetailsResponse(
        String publicId,
        WaybillPartyRole creatorRole,
        WaybillPartyRole payerRole,
        String description,
        BigDecimal declaredValue,
        String currency,
        WaybillStatus status,
        WaybillPaymentStatus paymentStatus,
        WaybillPricingResponse pricing,
        List<WaybillPartyResponse> parties,
        WaybillParcelResponse parcel,
        WaybillQuoteResponse selectedQuote,
        WaybillShipmentResponse shipment,
        List<WaybillInvitationSummaryResponse> invitations,
        LocalDateTime paymentDueAt,
        LocalDateTime fundedAt,
        LocalDateTime completedAt,
        String cancellationReason,
        LocalDateTime cancellationRequestedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
