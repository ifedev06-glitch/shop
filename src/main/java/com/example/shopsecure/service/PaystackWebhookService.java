package com.example.shopsecure.service;

import com.example.shopsecure.provider.common.WebhookPayload;
import com.example.shopsecure.provider.payment.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaystackWebhookService {

    private final PaymentProvider paymentProvider;
    private final RefundProvider refundProvider;
    private final PayoutProvider payoutProvider;
    private final OrderPaymentService paymentService;
    private final WaybillPaymentService waybillPaymentService;
    private final WaybillRefundService waybillRefundService;
    private final OrderSettlementService settlementService;
    private final WalletWithdrawalService withdrawalService;

    public void handle(WebhookPayload payload) {
        PaymentStatusUpdatedEvent paymentEvent = paymentProvider.handleWebhookEvent(payload);
        if (paymentEvent.relevant()) {
            if (waybillPaymentService.ownsReference(paymentEvent.reference())) {
                waybillPaymentService.handleEvent(paymentEvent);
            } else {
                paymentService.handleEvent(paymentEvent);
            }
            return;
        }
        RefundStatusUpdatedEvent refundEvent = refundProvider.handleRefundWebhookEvent(payload);
        if (refundEvent.relevant()) {
            waybillRefundService.handleEvent(refundEvent);
            return;
        }
        PayoutStatusUpdatedEvent payoutEvent = payoutProvider.handlePayoutWebhookEvent(payload);
        if (payoutEvent.relevant()) {
            if (!withdrawalService.applyPayoutStatus(payoutEvent)) {
                settlementService.applyPayoutStatus(payoutEvent);
            }
        }
    }
}
