package com.example.shopsecure.provider.payment;

import com.example.shopsecure.provider.common.WebhookPayload;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StubPaymentProviderTest {

    private final StubPaymentProvider provider = new StubPaymentProvider("http://localhost:3000/");

    @Test
    void initializesAndVerifiesCheckoutWithoutAnExternalProvider() {
        PaymentRequest request = new PaymentRequest(
                new BigDecimal("127500.00"),
                "buyer@example.com",
                "checkout-order-1-1234567890abcdef",
                "NGN",
                "http://localhost:3000/orders/token/test",
                Map.of("order_id", 1L)
        );

        PaymentInitResponse initialized = provider.initializeTransaction(request);
        PaymentVerificationResult verified = provider.verifyTransaction(request.reference());

        assertThat(initialized.reference()).isEqualTo(request.reference());
        assertThat(initialized.checkoutUrl())
                .isEqualTo("http://localhost:3000/loadtest/checkout/" + request.reference());
        assertThat(verified.reference()).isEqualTo(request.reference());
        assertThat(verified.status()).isEqualTo(PaymentTransactionStatus.SUCCESS);
        assertThat(verified.amount()).isEqualByComparingTo(request.amount());
        assertThat(verified.currency()).isEqualTo("NGN");
    }

    @Test
    void createsDeterministicVirtualAccountsAndSuccessfulPayouts() {
        VirtualAccountResponse first = provider.createVirtualAccount(new VirtualAccountRequest(
                "Ada Okafor",
                "ada@example.com",
                "+2348012345678",
                "user-42"
        ));
        VirtualAccountResponse repeated = provider.createVirtualAccount(new VirtualAccountRequest(
                "Ada Okafor",
                "ada@example.com",
                "+2348012345678",
                "user-42"
        ));
        PayoutResponse payout = provider.initiatePayout(new PayoutRequest(
                "loadtest-recipient-0000000000",
                new BigDecimal("125000.00"),
                "order_42_payout_1234567890",
                "Load-test payout"
        ));

        assertThat(first.accountNumber()).isEqualTo("9000000042");
        assertThat(repeated.accountNumber()).isEqualTo(first.accountNumber());
        assertThat(provider.listBanks()).containsExactly(new Bank("ShopSecure Load Test Bank", "999"));
        assertThat(payout.status()).isEqualTo(PayoutStatus.SUCCESS);
        assertThat(provider.verifyPayout(payout.reference()).status()).isEqualTo(PayoutStatus.SUCCESS);
    }

    @Test
    void storesPendingRefundsAndIgnoresRefundWebhooks() {
        RefundRequest request = new RefundRequest(
                "checkout-order-1-1234567890abcdef",
                new BigDecimal("1250.75"),
                "NGN",
                "Customer note",
                "Merchant note"
        );

        RefundResult initiated = provider.initiateRefund(request);
        RefundResult verified = provider.verifyRefund(initiated.refundId());
        RefundResult missing = provider.verifyRefund("missing-refund");
        RefundStatusUpdatedEvent webhook = provider.handleRefundWebhookEvent(new WebhookPayload("{}", Map.of()));

        assertThat(initiated.refundId()).startsWith("loadtest-refund-");
        assertThat(initiated.refundReference()).startsWith("loadtest-refund-reference-");
        assertThat(initiated.transactionId()).isNull();
        assertThat(initiated.transactionReference()).isEqualTo(request.transactionIdOrReference());
        assertThat(initiated.amount()).isEqualByComparingTo(request.amount());
        assertThat(initiated.currency()).isEqualTo(request.currency());
        assertThat(initiated.status()).isEqualTo(RefundStatus.PENDING);
        assertThat(initiated.merchantNote()).isEqualTo(request.merchantNote());
        assertThat(verified).isEqualTo(initiated);
        assertThat(missing.status()).isEqualTo(RefundStatus.UNKNOWN);
        assertThat(webhook.relevant()).isFalse();
        assertThat(webhook.status()).isEqualTo(RefundStatus.UNKNOWN);
    }
}
