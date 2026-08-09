package com.example.shopsecure.dto.response;

public record AuthChallengeResponse(
        String challengeId,
        String emailMasked,
        long expiresInSeconds,
        String message
) {
}
