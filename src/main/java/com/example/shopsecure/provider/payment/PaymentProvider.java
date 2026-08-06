package com.example.shopsecure.provider.payment;

import com.example.shopsecure.provider.common.WebhookPayload;

public interface PaymentProvider {
    PaymentInitResponse initializeTransaction(PaymentRequest request);

    PaymentVerificationResult verifyTransaction(String reference);

    VirtualAccountResponse createVirtualAccount(VirtualAccountRequest request);

    PaymentStatusUpdatedEvent handleWebhookEvent(WebhookPayload payload);
}
