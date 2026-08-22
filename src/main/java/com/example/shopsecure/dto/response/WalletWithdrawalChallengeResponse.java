package com.example.shopsecure.dto.response;

public record WalletWithdrawalChallengeResponse(
        String challengeId,
        String phoneNumberMasked,
        long expiresInSeconds
) {
}
