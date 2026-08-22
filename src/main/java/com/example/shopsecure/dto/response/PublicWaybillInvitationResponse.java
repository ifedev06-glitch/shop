package com.example.shopsecure.dto.response;

import com.example.shopsecure.entity.WaybillInvitationKind;
import com.example.shopsecure.entity.WaybillPartyRole;
import com.example.shopsecure.entity.WaybillPaymentStatus;
import com.example.shopsecure.entity.WaybillStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PublicWaybillInvitationResponse(
        String waybillPublicId,
        WaybillInvitationKind invitationKind,
        WaybillPartyRole targetPartyRole,
        WaybillPartyRole creatorRole,
        WaybillPartyRole payerRole,
        String description,
        BigDecimal declaredValue,
        String currency,
        WaybillStatus status,
        WaybillPaymentStatus paymentStatus,
        BigDecimal amountDue,
        boolean canPay,
        boolean detailsRequired,
        String inviteeEmailMasked,
        String inviteePhoneNumberMasked,
        LocalDateTime expiresAt
) {
}
