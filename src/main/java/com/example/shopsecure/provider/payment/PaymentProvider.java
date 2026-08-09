package com.example.shopsecure.provider.payment;

import com.example.shopsecure.provider.common.WebhookPayload;

public interface PaymentProvider extends VirtualAccountProvider {
    PaymentInitResponse initializeTransaction(PaymentRequest request);

    PaymentVerificationResult verifyTransaction(String reference);

    PaymentStatusUpdatedEvent handleWebhookEvent(WebhookPayload payload);
}
