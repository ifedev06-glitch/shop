package com.example.shopsecure.provider.payment;

public record PayoutResponse(
        String transferId,
        String reference,
        PayoutStatus status
) {
}
