package com.example.shopsecure.provider.payment;

public record PaymentInitResponse(
        String checkoutUrl,
        String reference
) {
}
