package com.example.shopsecure.provider.payment;

import com.example.shopsecure.provider.common.WebhookPayload;

public interface PayoutProvider {
    ResolvedBankAccount resolveBankAccount(BankAccountLookupRequest request);

    PayoutRecipientResponse createPayoutRecipient(PayoutRecipientRequest request);

    PayoutResponse initiatePayout(PayoutRequest request);

    PayoutStatusUpdatedEvent verifyPayout(String reference);

    PayoutStatusUpdatedEvent handlePayoutWebhookEvent(WebhookPayload payload);
}
