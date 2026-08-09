package com.example.shopsecure.provider.payment;

import com.example.shopsecure.provider.common.WebhookPayload;

import java.util.List;

public interface PayoutProvider {
    List<Bank> listBanks();

    ResolvedBankAccount resolveBankAccount(BankAccountLookupRequest request);

    PayoutRecipientResponse createPayoutRecipient(PayoutRecipientRequest request);

    PayoutResponse initiatePayout(PayoutRequest request);

    PayoutStatusUpdatedEvent verifyPayout(String reference);

    PayoutStatusUpdatedEvent handlePayoutWebhookEvent(WebhookPayload payload);
}
