package com.example.shopsecure.dto.response;

import com.example.shopsecure.entity.CreatorRole;

public record OtpChallengeResponse(
        String challengeId,
        CreatorRole counterpartyRole,
        String phoneNumberMasked,
        long expiresInSeconds
) {
}
