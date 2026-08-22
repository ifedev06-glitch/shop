package com.example.shopsecure.dto.response;

import com.example.shopsecure.entity.WaybillInvitationKind;
import com.example.shopsecure.entity.WaybillPartyRole;
import com.example.shopsecure.entity.WaybillPaymentStatus;
import com.example.shopsecure.entity.WaybillStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record WaybillAccessResponse(
        String waybillPublicId,
        String accessToken,
        WaybillInvitationKind invitationKind,
        WaybillPartyRole targetPartyRole,
        boolean canCompletePartyDetails,
        boolean canPay,
        boolean detailsCompleted,
        boolean paymentCompleted,
        WaybillStatus status,
        WaybillPaymentStatus paymentStatus,
        String description,
        BigDecimal declaredValue,
        String currency,
        WaybillPricingResponse pricing,
        List<WaybillPublicPartyResponse> parties,
        WaybillParcelResponse parcel,
        WaybillQuoteResponse selectedQuote,
        WaybillShipmentResponse shipment,
        LocalDateTime accessExpiresAt
) {
}
