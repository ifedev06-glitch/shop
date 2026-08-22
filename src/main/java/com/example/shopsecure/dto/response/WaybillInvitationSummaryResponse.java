package com.example.shopsecure.dto.response;

import com.example.shopsecure.entity.WaybillInvitationKind;
import com.example.shopsecure.entity.WaybillPartyRole;

import java.time.LocalDateTime;

public record WaybillInvitationSummaryResponse(
        Long invitationId,
        WaybillInvitationKind kind,
        WaybillPartyRole targetPartyRole,
        boolean canPay,
        String stagedFirstName,
        String stagedLastName,
        String stagedEmail,
        String stagedPhoneNumber,
        boolean active,
        LocalDateTime expiresAt,
        LocalDateTime verifiedAt,
        LocalDateTime detailsCompletedAt,
        LocalDateTime paymentCompletedAt,
        LocalDateTime revokedAt,
        LocalDateTime createdAt
) {
}
