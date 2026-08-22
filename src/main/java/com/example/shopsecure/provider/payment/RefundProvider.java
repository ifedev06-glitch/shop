package com.example.shopsecure.provider.payment;

import com.example.shopsecure.provider.common.WebhookPayload;

public interface RefundProvider {
    RefundResult initiateRefund(RefundRequest request);

    RefundResult verifyRefund(String refundId);

    RefundStatusUpdatedEvent handleRefundWebhookEvent(WebhookPayload payload);
}
