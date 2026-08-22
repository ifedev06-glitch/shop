package com.example.shopsecure.dto.response;

import com.example.shopsecure.entity.WaybillPartyRole;

public record WaybillOtpChallengeResponse(
        String challengeId,
        WaybillPartyRole partyRole,
        String emailMasked,
        String phoneNumberMasked,
        long expiresInSeconds
) {
}
