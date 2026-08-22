package com.example.shopsecure.dto.response;

import java.time.LocalDateTime;

public record WaybillInvitationReissueResponse(
        String paymentLink,
        LocalDateTime expiresAt
) {
}
