package com.example.shopsecure.service;

import com.example.shopsecure.provider.common.WebhookPayload;
import com.example.shopsecure.provider.payment.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaystackWebhookService {

    private final PaymentProvider paymentProvider;
    private final PayoutProvider payoutProvider;
    private final OrderPaymentService paymentService;
    private final OrderSettlementService settlementService;

    public void handle(WebhookPayload payload) {
        PaymentStatusUpdatedEvent paymentEvent = paymentProvider.handleWebhookEvent(payload);
        if (paymentEvent.relevant()) {
            paymentService.handleEvent(paymentEvent);
            return;
        }
        PayoutStatusUpdatedEvent payoutEvent = payoutProvider.handlePayoutWebhookEvent(payload);
        if (payoutEvent.relevant()) {
            settlementService.applyPayoutStatus(payoutEvent);
        }
    }
}
