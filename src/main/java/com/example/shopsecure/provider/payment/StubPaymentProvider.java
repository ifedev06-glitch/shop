package com.example.shopsecure.provider.payment;

import com.example.shopsecure.provider.common.WebhookPayload;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Profile("loadtest")
public class StubPaymentProvider implements PaymentProvider, PayoutProvider, RefundProvider {

    private static final String BANK_CODE = "999";
    private static final String BANK_NAME = "ShopSecure Load Test Bank";

    private final String checkoutBaseUrl;
    private final ConcurrentMap<String, StubPayment> payments = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PayoutResponse> payouts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RefundResult> refunds = new ConcurrentHashMap<>();
    private final AtomicLong recipientSequence = new AtomicLong();
    private final AtomicLong refundSequence = new AtomicLong();

    public StubPaymentProvider(@Value("${app.base-url:http://localhost:3000}") String appBaseUrl) {
        this.checkoutBaseUrl = appBaseUrl.replaceAll("/+$", "");
    }

    @Override
    public PaymentInitResponse initializeTransaction(PaymentRequest request) {
        payments.putIfAbsent(request.reference(), new StubPayment(request, Instant.now()));
        return new PaymentInitResponse(
                checkoutBaseUrl + "/loadtest/checkout/" + request.reference(),
                "loadtest-access-" + request.reference(),
                request.reference()
        );
    }

    @Override
    public PaymentVerificationResult verifyTransaction(String reference) {
        StubPayment payment = payments.get(reference);
        if (payment == null) {
            return new PaymentVerificationResult(
                    null,
                    reference,
                    PaymentTransactionStatus.UNKNOWN,
                    null,
                    null,
                    null,
                    Instant.now()
            );
        }
        return new PaymentVerificationResult(
                "loadtest-event-" + reference,
                reference,
                PaymentTransactionStatus.SUCCESS,
                payment.request().amount(),
                payment.request().currency(),
                "loadtest",
                payment.initializedAt()
        );
    }

    @Override
    public VirtualAccountResponse createVirtualAccount(VirtualAccountRequest request) {
        return new VirtualAccountResponse(
                accountNumber(request.customerReference()),
                BANK_NAME,
                request.customerName(),
                null
        );
    }

    @Override
    public PaymentStatusUpdatedEvent handleWebhookEvent(WebhookPayload payload) {
        return PaymentStatusUpdatedEvent.ignored();
    }

    @Override
    public List<Bank> listBanks() {
        return List.of(new Bank(BANK_NAME, BANK_CODE));
    }

    @Override
    public ResolvedBankAccount resolveBankAccount(BankAccountLookupRequest request) {
        String lastFour = request.accountNumber().substring(request.accountNumber().length() - 4);
        return new ResolvedBankAccount(
                "Load Test Account " + lastFour,
                request.accountNumber(),
                request.bankCode(),
                BANK_NAME
        );
    }

    @Override
    public PayoutRecipientResponse createPayoutRecipient(PayoutRecipientRequest request) {
        return new PayoutRecipientResponse("loadtest-recipient-" + recipientSequence.incrementAndGet());
    }

    @Override
    public PayoutResponse initiatePayout(PayoutRequest request) {
        PayoutResponse payout = new PayoutResponse(
                "loadtest-transfer-" + request.reference(),
                request.reference(),
                PayoutStatus.SUCCESS,
                request.amount(),
                "NGN",
                request.recipientId()
        );
        payouts.putIfAbsent(request.reference(), payout);
        return payouts.get(request.reference());
    }

    @Override
    public PayoutStatusUpdatedEvent verifyPayout(String reference) {
        PayoutResponse payout = payouts.get(reference);
        if (payout == null) {
            return null;
        }
        return new PayoutStatusUpdatedEvent(
                payout.reference(),
                payout.transferId(),
                payout.status(),
                payout.amount(),
                payout.currency(),
                payout.recipientId(),
                Instant.now(),
                true
        );
    }

    @Override
    public PayoutStatusUpdatedEvent handlePayoutWebhookEvent(WebhookPayload payload) {
        return PayoutStatusUpdatedEvent.ignored();
    }

    @Override
    public RefundResult initiateRefund(RefundRequest request) {
        String sequence = Long.toString(refundSequence.incrementAndGet());
        String refundId = "loadtest-refund-" + sequence;
        RefundResult refund = new RefundResult(
                refundId,
                "loadtest-refund-reference-" + sequence,
                null,
                request.transactionIdOrReference(),
                request.amount(),
                request.currency(),
                RefundStatus.PENDING,
                request.merchantNote(),
                Instant.now()
        );
        refunds.put(refundId, refund);
        return refund;
    }

    @Override
    public RefundResult verifyRefund(String refundId) {
        RefundResult refund = refunds.get(refundId);
        if (refund != null) {
            return refund;
        }
        return new RefundResult(
                refundId,
                null,
                null,
                null,
                null,
                null,
                RefundStatus.UNKNOWN,
                null,
                Instant.now()
        );
    }

    @Override
    public RefundStatusUpdatedEvent handleRefundWebhookEvent(WebhookPayload payload) {
        return RefundStatusUpdatedEvent.ignored();
    }

    private String accountNumber(String customerReference) {
        String digits = customerReference == null ? "" : customerReference.replaceAll("\\D", "");
        long value;
        try {
            value = digits.isBlank()
                    ? Integer.toUnsignedLong(String.valueOf(customerReference).hashCode())
                    : Long.parseLong(digits);
        } catch (NumberFormatException exception) {
            value = Integer.toUnsignedLong(String.valueOf(customerReference).hashCode());
        }
        return String.format(Locale.ROOT, "9%09d", value % 1_000_000_000L);
    }

    private record StubPayment(PaymentRequest request, Instant initializedAt) {
    }
}
