package com.example.shopsecure.integration;

import com.example.shopsecure.ShopsecureApplication;
import com.example.shopsecure.entity.CreatorRole;
import com.example.shopsecure.entity.EscrowReleasePolicy;
import com.example.shopsecure.entity.Order;
import com.example.shopsecure.entity.OrderFundingMode;
import com.example.shopsecure.entity.OrderPaymentAttempt;
import com.example.shopsecure.entity.OrderSettlement;
import com.example.shopsecure.entity.OrderShipment;
import com.example.shopsecure.entity.OrderStatus;
import com.example.shopsecure.entity.PaymentAttemptStatus;
import com.example.shopsecure.entity.PaymentReceipt;
import com.example.shopsecure.entity.PaymentReceiptClassification;
import com.example.shopsecure.entity.PaymentStatus;
import com.example.shopsecure.entity.Role;
import com.example.shopsecure.entity.SettlementStatus;
import com.example.shopsecure.entity.SettlementType;
import com.example.shopsecure.entity.User;
import com.example.shopsecure.entity.VirtualAccount;
import com.example.shopsecure.entity.Wallet;
import com.example.shopsecure.entity.WalletWithdrawal;
import com.example.shopsecure.entity.WalletWithdrawalStatus;
import com.example.shopsecure.provider.common.WebhookPayload;
import com.example.shopsecure.provider.payment.Bank;
import com.example.shopsecure.provider.payment.BankAccountLookupRequest;
import com.example.shopsecure.provider.payment.PaystackPaymentProvider;
import com.example.shopsecure.provider.payment.PayoutProvider;
import com.example.shopsecure.provider.payment.PayoutRecipientRequest;
import com.example.shopsecure.provider.payment.PayoutRecipientResponse;
import com.example.shopsecure.provider.payment.PayoutRequest;
import com.example.shopsecure.provider.payment.PayoutResponse;
import com.example.shopsecure.provider.payment.PayoutStatusUpdatedEvent;
import com.example.shopsecure.provider.payment.ResolvedBankAccount;
import com.example.shopsecure.provider.shipping.ShipmentStatus;
import com.example.shopsecure.provider.shipping.ShipmentTrackingStatus;
import com.example.shopsecure.provider.shipping.ShippingProvider;
import com.example.shopsecure.provider.shipping.ShippingProviderCode;
import com.example.shopsecure.repository.GuestOrderAccessTokenRepository;
import com.example.shopsecure.repository.OrderCounterpartyRepository;
import com.example.shopsecure.repository.OrderParcelRepository;
import com.example.shopsecure.repository.OrderPaymentAttemptRepository;
import com.example.shopsecure.repository.OrderRepository;
import com.example.shopsecure.repository.OrderSettlementRepository;
import com.example.shopsecure.repository.OrderShipmentRepository;
import com.example.shopsecure.repository.OrderStatusHistoryRepository;
import com.example.shopsecure.repository.OtpChallengeRepository;
import com.example.shopsecure.repository.PaymentReceiptRepository;
import com.example.shopsecure.repository.ProcessedWebhookEventRepository;
import com.example.shopsecure.repository.ShippingQuoteRepository;
import com.example.shopsecure.repository.UserRepository;
import com.example.shopsecure.repository.VirtualAccountRepository;
import com.example.shopsecure.repository.WalletRepository;
import com.example.shopsecure.repository.WalletTransactionRepository;
import com.example.shopsecure.repository.WalletWithdrawalRepository;
import com.example.shopsecure.service.ShippingProviderRoutingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = {
                ShopsecureApplication.class,
                TestConfig.class,
                WebhookSecurityIntegrationTest.ProviderTestConfig.class
        },
        properties = {
                "paystack.secret-key=test-paystack-webhook-secret",
                "shipping.terminal-africa.enabled=true",
                "shipping.terminal-africa.secret-key=test-terminal-webhook-secret",
                "shipping.terminal-africa.base-url=https://terminal.invalid.test/v1",
                "payout.reconciliation.initial-delay-ms=3600000",
                "payout.reconciliation.delay-ms=3600000"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebhookSecurityIntegrationTest {

    private static final String PAYSTACK_PATH = "/api/webhooks/paystack";
    private static final String TERMINAL_PATH = "/api/webhooks/terminal";
    private static final String CHOWDECK_PATH = "/api/webhooks/chowdeck";
    private static final String PAYSTACK_SECRET = "test-paystack-webhook-secret";
    private static final String TERMINAL_SECRET = "test-terminal-webhook-secret";
    private static final String BANK_REQUEST = """
            {"accountNumber":"0123456789","bankCode":"999"}
            """;
    private static final AtomicLong FIXTURE_SEQUENCE = new AtomicLong();

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderTestConfig {

        @Bean("webhookSecurityPayoutProvider")
        @Primary
        PayoutProvider webhookSecurityPayoutProvider(PaystackPaymentProvider paystackProvider) {
            return new PayoutProvider() {
                @Override
                public List<Bank> listBanks() {
                    return List.of(new Bank("Test Bank", "999"));
                }

                @Override
                public ResolvedBankAccount resolveBankAccount(BankAccountLookupRequest request) {
                    return new ResolvedBankAccount(
                            "Test Vendor",
                            request.accountNumber(),
                            request.bankCode(),
                            "Test Bank"
                    );
                }

                @Override
                public PayoutRecipientResponse createPayoutRecipient(PayoutRecipientRequest request) {
                    throw new UnsupportedOperationException("Payout recipient creation is not available in this test");
                }

                @Override
                public PayoutResponse initiatePayout(PayoutRequest request) {
                    throw new UnsupportedOperationException("Payout initiation is not available in this test");
                }

                @Override
                public PayoutStatusUpdatedEvent verifyPayout(String reference) {
                    throw new UnsupportedOperationException("Payout verification is not available in this test");
                }

                @Override
                public PayoutStatusUpdatedEvent handlePayoutWebhookEvent(WebhookPayload payload) {
                    return paystackProvider.handlePayoutWebhookEvent(payload);
                }
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private WalletRepository walletRepository;
    @Autowired
    private VirtualAccountRepository virtualAccountRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderCounterpartyRepository counterpartyRepository;
    @Autowired
    private OrderParcelRepository parcelRepository;
    @Autowired
    private GuestOrderAccessTokenRepository guestTokenRepository;
    @Autowired
    private OrderShipmentRepository shipmentRepository;
    @Autowired
    private ShippingQuoteRepository quoteRepository;
    @Autowired
    private PaymentReceiptRepository receiptRepository;
    @Autowired
    private OrderPaymentAttemptRepository paymentAttemptRepository;
    @Autowired
    private OrderSettlementRepository settlementRepository;
    @Autowired
    private ProcessedWebhookEventRepository processedWebhookRepository;
    @Autowired
    private OrderStatusHistoryRepository historyRepository;
    @Autowired
    private OtpChallengeRepository otpChallengeRepository;
    @Autowired
    private WalletTransactionRepository walletTransactionRepository;
    @Autowired
    private WalletWithdrawalRepository withdrawalRepository;
    @MockitoBean
    private ShippingProviderRoutingService shippingProviderRoutingService;

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void paystackWebhookIsPublicButRejectsUnsignedAndTamperedPayloads() throws Exception {
        String unsignedBody = "{\"event\":\"customer.created\",\"data\":{}}";
        mockMvc.perform(post(PAYSTACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unsignedBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid webhook signature"));

        String signedBody = "{\"event\":\"customer.created\",\"data\":{}}";
        String tamperedBody = "{\"event\":\"customer.deleted\",\"data\":{}}";
        mockMvc.perform(post(PAYSTACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Paystack-Signature", sign(signedBody, PAYSTACK_SECRET))
                        .content(tamperedBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid webhook signature"));
    }

    @Test
    void signedIrrelevantPaystackEventIsAcknowledgedWithoutMutatingPayments() throws Exception {
        User creator = createUser("irrelevant-paystack");
        Order order = createOrder(
                creator,
                CreatorRole.BUYER,
                OrderStatus.AWAITING_PAYMENT,
                PaymentStatus.PENDING,
                SettlementStatus.NOT_STARTED
        );
        long receiptsBefore = receiptRepository.count();
        String body = "{\"event\":\"customeridentification.success\",\"data\":{}}";

        mockMvc.perform(post(PAYSTACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Paystack-Signature", sign(body, PAYSTACK_SECRET))
                        .content(body))
                .andExpect(status().isOk());

        Order unchanged = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(receiptRepository.count()).isEqualTo(receiptsBefore);
        assertThat(unchanged.getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        assertThat(unchanged.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(unchanged.getFundedAt()).isNull();
    }

    @Test
    void signedPaystackChargeRoutesThroughPaymentStateHandling() throws Exception {
        User creator = createUser("paystack-charge");
        Order order = createOrder(
                creator,
                CreatorRole.BUYER,
                OrderStatus.AWAITING_PAYMENT,
                PaymentStatus.PENDING,
                SettlementStatus.NOT_STARTED
        );
        createVirtualAccount(creator, "1234567890");
        String body = """
                {
                  "event":"charge.success",
                  "data":{
                    "id":"evt-charge-underpaid",
                    "reference":"pay-charge-underpaid",
                    "amount":5000,
                    "currency":"NGN",
                    "channel":"dedicated_nuban",
                    "authorization":{"receiver_bank_account_number":"1234567890"},
                    "paid_at":"2026-08-07T10:15:30Z"
                  }
                }
                """;

        mockMvc.perform(post(PAYSTACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Paystack-Signature", sign(body, PAYSTACK_SECRET))
                        .content(body))
                .andExpect(status().isOk());

        Order underpaid = orderRepository.findById(order.getId()).orElseThrow();
        PaymentReceipt receipt = receiptRepository
                .findTopByOrder_IdOrderByCreatedAtDesc(order.getId())
                .orElseThrow();
        assertThat(receipt.getProviderEventKey()).isEqualTo("evt-charge-underpaid");
        assertThat(receipt.getClassification()).isEqualTo(PaymentReceiptClassification.UNDERPAID);
        assertThat(receipt.getAmount()).isEqualByComparingTo("50.00");
        assertThat(underpaid.getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        assertThat(underpaid.getPaymentStatus()).isEqualTo(PaymentStatus.UNDERPAID);
        assertThat(underpaid.getFundedAt()).isNull();
    }

    @Test
    void signedCardChargeFundsOnlyTheCheckoutAttemptWithItsReference() throws Exception {
        User creator = createUser("paystack-checkout-charge");
        Order order = createOrder(
                creator,
                CreatorRole.VENDOR,
                OrderStatus.AWAITING_PAYMENT,
                PaymentStatus.PENDING,
                SettlementStatus.NOT_STARTED
        );
        order.setFundingMode(OrderFundingMode.PAYSTACK_CHECKOUT);
        order.setPaymentReference(null);
        orderRepository.save(order);
        String reference = "checkout-webhook-" + order.getId();
        Instant now = Instant.now();
        paymentAttemptRepository.save(OrderPaymentAttempt.builder()
                .order(order)
                .provider("PAYSTACK")
                .providerReference(reference)
                .authorizationUrl("https://checkout.paystack.test/" + reference)
                .accessCode("access-" + reference)
                .customerEmail("guest-buyer@example.test")
                .amount(new BigDecimal("100.00"))
                .currency("NGN")
                .status(PaymentAttemptStatus.INITIALIZED)
                .createdAt(now)
                .updatedAt(now)
                .build());
        String body = """
                {
                  "event":"charge.success",
                  "data":{
                    "id":"evt-checkout-success",
                    "reference":"%s",
                    "amount":10000,
                    "currency":"NGN",
                    "channel":"card",
                    "paid_at":"2026-08-07T10:15:30Z"
                  }
                }
                """.formatted(reference);

        mockMvc.perform(post(PAYSTACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Paystack-Signature", sign(body, PAYSTACK_SECRET))
                        .content(body))
                .andExpect(status().isOk());

        Order funded = orderRepository.findById(order.getId()).orElseThrow();
        PaymentReceipt receipt = receiptRepository
                .findTopByOrder_IdOrderByCreatedAtDesc(order.getId())
                .orElseThrow();
        assertThat(funded.getPaymentStatus()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(funded.getStatus()).isEqualTo(OrderStatus.SHIPMENT_BOOKING);
        assertThat(receipt.getClassification()).isEqualTo(PaymentReceiptClassification.MATCHED);
        assertThat(receipt.getProviderReference()).isEqualTo(reference);
        assertThat(paymentAttemptRepository.findAll())
                .singleElement()
                .satisfies(attempt -> assertThat(attempt.getStatus())
                        .isEqualTo(PaymentAttemptStatus.SUCCESS));
    }

    @Test
    void signedPaystackTransferRoutesThroughSettlementStateHandling() throws Exception {
        User creator = createUser("paystack-transfer");
        Order order = createOrder(
                creator,
                CreatorRole.BUYER,
                OrderStatus.PAYOUT_PENDING,
                PaymentStatus.CONFIRMED,
                SettlementStatus.PENDING
        );
        order.setVendorPayoutRecipientCode("RCP_WEBHOOK_SUCCESS");
        order = orderRepository.save(order);
        String payoutReference = "shopsecure_order_" + order.getId() + "_payout";
        Instant createdAt = Instant.parse("2026-08-07T09:00:00Z");
        settlementRepository.save(OrderSettlement.builder()
                .order(order)
                .settlementType(SettlementType.EXTERNAL_PAYOUT)
                .amount(order.getItemAmount())
                .status(SettlementStatus.PENDING)
                .idempotencyKey("webhook-transfer-fixture")
                .payoutReference(payoutReference)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build());
        String body = """
                {
                  "event":"transfer.success",
                  "data":{
                    "reference":"%s",
                    "transfer_code":"TRF_WEBHOOK_SUCCESS",
                    "amount":8000,
                    "currency":"NGN",
                    "recipient":{"recipient_code":"RCP_WEBHOOK_SUCCESS"},
                    "transferred_at":"2026-08-07T10:30:00Z"
                  }
                }
                """.formatted(payoutReference);

        mockMvc.perform(post(PAYSTACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Paystack-Signature", sign(body, PAYSTACK_SECRET))
                        .content(body))
                .andExpect(status().isOk());

        Order completedOrder = orderRepository.findById(order.getId()).orElseThrow();
        OrderSettlement completedSettlement = settlementRepository.findByOrder_Id(order.getId()).orElseThrow();
        assertThat(completedOrder.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(completedOrder.getSettlementStatus()).isEqualTo(SettlementStatus.COMPLETED);
        assertThat(completedOrder.getCompletedAt()).isNotNull();
        assertThat(completedSettlement.getStatus()).isEqualTo(SettlementStatus.COMPLETED);
        assertThat(completedSettlement.getTransferId()).isEqualTo("TRF_WEBHOOK_SUCCESS");
        assertThat(completedSettlement.getUpdatedAt()).isEqualTo(Instant.parse("2026-08-07T10:30:00Z"));
    }

    @Test
    void signedTransferWithKnownTransferIdAndWrongReferenceRequiresManualReview() throws Exception {
        User creator = createUser("paystack-transfer-mismatch");
        Order order = createOrder(
                creator,
                CreatorRole.BUYER,
                OrderStatus.PAYOUT_PENDING,
                PaymentStatus.CONFIRMED,
                SettlementStatus.PENDING
        );
        order.setVendorPayoutRecipientCode("RCP_WEBHOOK_MISMATCH");
        order = orderRepository.save(order);
        String payoutReference = "shopsecure_order_" + order.getId() + "_payout";
        Instant createdAt = Instant.parse("2026-08-07T09:00:00Z");
        settlementRepository.save(OrderSettlement.builder()
                .order(order)
                .settlementType(SettlementType.EXTERNAL_PAYOUT)
                .amount(order.getItemAmount())
                .status(SettlementStatus.PENDING)
                .idempotencyKey("webhook-transfer-mismatch-fixture")
                .payoutReference(payoutReference)
                .transferId("TRF_WEBHOOK_MISMATCH")
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build());
        String body = """
                {
                  "event":"transfer.success",
                  "data":{
                    "reference":"unexpected-webhook-reference",
                    "transfer_code":"TRF_WEBHOOK_MISMATCH",
                    "amount":8000,
                    "currency":"NGN",
                    "recipient":{"recipient_code":"RCP_WEBHOOK_MISMATCH"},
                    "transferred_at":"2026-08-07T10:30:00Z"
                  }
                }
                """;

        mockMvc.perform(post(PAYSTACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Paystack-Signature", sign(body, PAYSTACK_SECRET))
                        .content(body))
                .andExpect(status().isOk());

        OrderSettlement settlement = settlementRepository.findByOrder_Id(order.getId()).orElseThrow();
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.REQUIRES_ACTION);
        assertThat(settlement.getUnexpectedPayoutReference()).isEqualTo("unexpected-webhook-reference");
        assertThat(settlement.getUnexpectedTransferId()).isNull();
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getSettlementStatus())
                .isEqualTo(SettlementStatus.REQUIRES_ACTION);
    }

    @Test
    void conflictingValidReferenceAndTransferIdCannotCompleteTheReferenceMatchedPayout() throws Exception {
        User firstCreator = createUser("paystack-transfer-owner");
        User secondCreator = createUser("paystack-reference-owner");
        Order transferOwner = createOrder(
                firstCreator,
                CreatorRole.BUYER,
                OrderStatus.PAYOUT_PENDING,
                PaymentStatus.CONFIRMED,
                SettlementStatus.PENDING
        );
        Order referenceOwner = createOrder(
                secondCreator,
                CreatorRole.BUYER,
                OrderStatus.PAYOUT_PENDING,
                PaymentStatus.CONFIRMED,
                SettlementStatus.PENDING
        );
        transferOwner.setVendorPayoutRecipientCode("RCP_COLLISION");
        referenceOwner.setVendorPayoutRecipientCode("RCP_COLLISION");
        orderRepository.saveAll(List.of(transferOwner, referenceOwner));
        Instant createdAt = Instant.parse("2026-08-07T09:00:00Z");
        String transferOwnerReference = "shopsecure_order_" + transferOwner.getId() + "_payout";
        String referenceOwnerReference = "shopsecure_order_" + referenceOwner.getId() + "_payout";
        settlementRepository.save(OrderSettlement.builder()
                .order(transferOwner)
                .settlementType(SettlementType.EXTERNAL_PAYOUT)
                .amount(transferOwner.getItemAmount())
                .status(SettlementStatus.PENDING)
                .idempotencyKey("webhook-transfer-owner")
                .payoutReference(transferOwnerReference)
                .transferId("TRF_IDENTITY_COLLISION")
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build());
        settlementRepository.save(OrderSettlement.builder()
                .order(referenceOwner)
                .settlementType(SettlementType.EXTERNAL_PAYOUT)
                .amount(referenceOwner.getItemAmount())
                .status(SettlementStatus.PENDING)
                .idempotencyKey("webhook-reference-owner")
                .payoutReference(referenceOwnerReference)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build());
        String body = """
                {
                  "event":"transfer.success",
                  "data":{
                    "reference":"%s",
                    "transfer_code":"TRF_IDENTITY_COLLISION",
                    "amount":8000,
                    "currency":"NGN",
                    "recipient":{"recipient_code":"RCP_COLLISION"},
                    "transferred_at":"2026-08-07T10:30:00Z"
                  }
                }
                """.formatted(referenceOwnerReference);

        mockMvc.perform(post(PAYSTACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Paystack-Signature", sign(body, PAYSTACK_SECRET))
                        .content(body))
                .andExpect(status().isOk());

        OrderSettlement transferMatched = settlementRepository.findByOrder_Id(transferOwner.getId()).orElseThrow();
        OrderSettlement referenceMatched = settlementRepository.findByOrder_Id(referenceOwner.getId()).orElseThrow();
        assertThat(transferMatched.getStatus()).isEqualTo(SettlementStatus.REQUIRES_ACTION);
        assertThat(transferMatched.getUnexpectedPayoutReference()).isEqualTo(referenceOwnerReference);
        assertThat(referenceMatched.getStatus()).isEqualTo(SettlementStatus.PENDING);
        assertThat(referenceMatched.getTransferId()).isNull();
        assertThat(orderRepository.findById(referenceOwner.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAYOUT_PENDING);
    }

    @Test
    void orderTransferIdentityCannotCompleteAReferenceMatchedWalletWithdrawal() throws Exception {
        User orderCreator = createUser("paystack-order-transfer-owner");
        Order transferOwner = createOrder(
                orderCreator,
                CreatorRole.BUYER,
                OrderStatus.PAYOUT_PENDING,
                PaymentStatus.CONFIRMED,
                SettlementStatus.PENDING
        );
        transferOwner.setVendorPayoutRecipientCode("RCP_CROSS_DOMAIN");
        transferOwner = orderRepository.save(transferOwner);
        Instant createdAt = Instant.parse("2026-08-07T09:00:00Z");
        String transferOwnerReference = "shopsecure_order_" + transferOwner.getId() + "_payout";
        settlementRepository.save(OrderSettlement.builder()
                .order(transferOwner)
                .settlementType(SettlementType.EXTERNAL_PAYOUT)
                .amount(transferOwner.getItemAmount())
                .status(SettlementStatus.PENDING)
                .idempotencyKey("cross-domain-order-transfer")
                .payoutReference(transferOwnerReference)
                .transferId("TRF_CROSS_DOMAIN")
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build());
        User withdrawalOwner = createUser("paystack-wallet-reference-owner");
        Wallet wallet = walletRepository.save(Wallet.builder()
                .user(withdrawalOwner)
                .balance(new BigDecimal("1000.00"))
                .currency("NGN")
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build());
        String withdrawalReference = "withdrawal-cross-domain-reference";
        WalletWithdrawal withdrawal = withdrawalRepository.save(WalletWithdrawal.builder()
                .wallet(wallet)
                .amount(new BigDecimal("80.00"))
                .currency("NGN")
                .status(WalletWithdrawalStatus.PENDING)
                .idempotencyKey("cross-domain-wallet-reference")
                .reference(withdrawalReference)
                .payoutRecipientCode("RCP_CROSS_DOMAIN")
                .accountNumberLast4("6789")
                .bankName("Test Bank")
                .accountName("Test Recipient")
                .dispatchAttempt(1)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build());
        String body = """
                {
                  "event":"transfer.success",
                  "data":{
                    "reference":"%s",
                    "transfer_code":"TRF_CROSS_DOMAIN",
                    "amount":8000,
                    "currency":"NGN",
                    "recipient":{"recipient_code":"RCP_CROSS_DOMAIN"},
                    "transferred_at":"2026-08-07T10:30:00Z"
                  }
                }
                """.formatted(withdrawalReference);

        mockMvc.perform(post(PAYSTACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Paystack-Signature", sign(body, PAYSTACK_SECRET))
                        .content(body))
                .andExpect(status().isOk());

        assertThat(settlementRepository.findByOrder_Id(transferOwner.getId()).orElseThrow().getStatus())
                .isEqualTo(SettlementStatus.REQUIRES_ACTION);
        WalletWithdrawal unchangedWithdrawal = withdrawalRepository.findById(withdrawal.getId()).orElseThrow();
        assertThat(unchangedWithdrawal.getStatus()).isEqualTo(WalletWithdrawalStatus.PENDING);
        assertThat(unchangedWithdrawal.getTransferId()).isNull();
    }

    @Test
    void terminalWebhookIsPublicButRejectsUnsignedAndTamperedPayloads() throws Exception {
        String unsignedBody = "{\"event\":\"address.updated\",\"data\":{}}";
        mockMvc.perform(post(TERMINAL_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unsignedBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid Terminal Africa webhook signature"));

        String signedBody = "{\"event\":\"address.updated\",\"data\":{}}";
        String tamperedBody = "{\"event\":\"address.deleted\",\"data\":{}}";
        mockMvc.perform(post(TERMINAL_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Terminal-Signature", sign(signedBody, TERMINAL_SECRET))
                        .content(tamperedBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid Terminal Africa webhook signature"));
    }

    @Test
    void signedIrrelevantTerminalEventIsAcknowledged() throws Exception {
        long processedEventsBefore = processedWebhookRepository.count();
        String body = "{\"event\":\"address.updated\",\"data\":{}}";

        mockMvc.perform(post(TERMINAL_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Terminal-Signature", sign(body, TERMINAL_SECRET))
                        .content(body))
                .andExpect(status().isOk());

        assertThat(processedWebhookRepository.count()).isEqualTo(processedEventsBefore);
    }

    @Test
    void chowdeckWebhookIsPublicButAcceptsOnlyNestedDeliveryReference() throws Exception {
        mockMvc.perform(post(CHOWDECK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "category":"ORDER_PICKED_UP",
                                  "reference":"attacker-controlled-root-reference",
                                  "payload":{
                                    "reference":"order-404",
                                    "status":"picked_up",
                                    "timeline":[{"action":"ORDER_PICKED_UP"}]
                                  }
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post(CHOWDECK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reference":"order-404","status":"picked_up"}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(CHOWDECK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"payload":{"reference":"../../order-404"}}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void chowdeckCallbackCannotForgePickupAndCanonicalPickupPaysItemAmountOnce() throws Exception {
        User creator = createUser("chowdeck-canonical-pickup");
        Order order = createOrder(
                creator,
                CreatorRole.VENDOR,
                OrderStatus.READY_FOR_DELIVERY,
                PaymentStatus.CONFIRMED,
                SettlementStatus.NOT_STARTED
        );
        order.setEscrowReleasePolicy(EscrowReleasePolicy.CARRIER_PICKUP);
        order.setVendorPayoutRecipientCode("RCP_CHOWDECK_CANONICAL");
        order = orderRepository.save(order);
        String reference = "order-" + order.getId();
        Instant confirmedAt = Instant.parse("2026-08-21T10:00:00Z");
        Instant pickupAt = Instant.parse("2026-08-21T10:05:00Z");
        shipmentRepository.save(OrderShipment.builder()
                .order(order)
                .provider(ShippingProviderCode.CHOWDECK)
                .providerShipmentId(reference)
                .status(ShipmentStatus.PENDING)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        ShippingProvider canonicalProvider = mock(ShippingProvider.class);
        when(shippingProviderRoutingService.require(ShippingProviderCode.CHOWDECK))
                .thenReturn(canonicalProvider);
        when(canonicalProvider.trackShipment(reference)).thenReturn(
                new ShipmentTrackingStatus(
                        reference,
                        ShipmentStatus.CONFIRMED,
                        confirmedAt,
                        List.of(),
                        reference,
                        null
                ),
                new ShipmentTrackingStatus(
                        reference,
                        ShipmentStatus.IN_TRANSIT,
                        pickupAt,
                        List.of(),
                        reference,
                        null
                ),
                new ShipmentTrackingStatus(
                        reference,
                        ShipmentStatus.IN_TRANSIT,
                        pickupAt,
                        List.of(),
                        reference,
                        null
                )
        );
        String forgedPickupBody = """
                {
                  "category":"ORDER_PICKED_UP",
                  "payload":{
                    "reference":"%s",
                    "status":"picked_up",
                    "updated_at":"2026-08-21T10:05:00Z"
                  }
                }
                """.formatted(reference);

        mockMvc.perform(post(CHOWDECK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgedPickupBody))
                .andExpect(status().isOk());

        Order beforeCanonicalPickup = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(beforeCanonicalPickup.getCarrierPickupConfirmedAt()).isNull();
        assertThat(settlementRepository.findByOrder_Id(order.getId())).isEmpty();

        mockMvc.perform(post(CHOWDECK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgedPickupBody))
                .andExpect(status().isOk());
        mockMvc.perform(post(CHOWDECK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgedPickupBody))
                .andExpect(status().isOk());

        Order afterCanonicalPickup = orderRepository.findById(order.getId()).orElseThrow();
        OrderSettlement settlement = settlementRepository.findByOrder_Id(order.getId()).orElseThrow();
        assertThat(afterCanonicalPickup.getCarrierPickupConfirmedAt()).isEqualTo(pickupAt);
        assertThat(settlement.getAmount()).isEqualByComparingTo("80.00");
        assertThat(settlement.getPayoutReference())
                .isEqualTo("shopsecure_order_" + order.getId() + "_payout");
        assertThat(settlementRepository.count()).isEqualTo(1);
        verify(canonicalProvider, times(3)).trackShipment(reference);
    }

    @Test
    void neighboringWebhookPathsAreNotPublic() {
        assertAll(
                () -> assertAuthenticationRequired(post(PAYSTACK_PATH + "/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")),
                () -> assertAuthenticationRequired(post(TERMINAL_PATH + "/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")),
                () -> assertAuthenticationRequired(post(CHOWDECK_PATH + "/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")),
                () -> assertAuthenticationRequired(post("/api/webhooks/unregistered")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
        );
    }

    @Test
    void nonPostWebhookMethodsAreNotPublic() {
        assertAll(
                () -> assertAuthenticationRequired(get(PAYSTACK_PATH)),
                () -> assertAuthenticationRequired(put(PAYSTACK_PATH)),
                () -> assertAuthenticationRequired(patch(PAYSTACK_PATH)),
                () -> assertAuthenticationRequired(delete(PAYSTACK_PATH)),
                () -> assertAuthenticationRequired(get(TERMINAL_PATH)),
                () -> assertAuthenticationRequired(put(TERMINAL_PATH)),
                () -> assertAuthenticationRequired(patch(TERMINAL_PATH)),
                () -> assertAuthenticationRequired(delete(TERMINAL_PATH)),
                () -> assertAuthenticationRequired(get(CHOWDECK_PATH)),
                () -> assertAuthenticationRequired(put(CHOWDECK_PATH)),
                () -> assertAuthenticationRequired(patch(CHOWDECK_PATH)),
                () -> assertAuthenticationRequired(delete(CHOWDECK_PATH))
        );
    }

    @Test
    void memberBankResolutionRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/bank-accounts/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BANK_REQUEST))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));

        mockMvc.perform(post("/api/bank-accounts/resolve")
                        .with(user("member@example.test").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BANK_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumberMasked").value("******6789"))
                .andExpect(jsonPath("$.accountName").value("Test Vendor"));
    }

    @Test
    void paymentLinksDoNotAcceptVendorBankDetails() throws Exception {
        mockMvc.perform(post("/orders/token/{shareToken}/bank-account/resolve", "unknown-share-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BANK_REQUEST))
                .andExpect(status().isNotFound());
    }

    private void assertAuthenticationRequired(MockHttpServletRequestBuilder request) throws Exception {
        int responseStatus = mockMvc.perform(request).andReturn().getResponse().getStatus();
        assertThat(responseStatus).isIn(401, 403);
    }

    private String sign(String body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private User createUser(String prefix) {
        long sequence = FIXTURE_SEQUENCE.incrementAndGet();
        return userRepository.save(User.builder()
                .firstname("Test")
                .lastname("Member")
                .email(prefix + "-" + sequence + "@example.test")
                .phoneNumber("2348%09d".formatted(sequence % 1_000_000_000L))
                .password("encoded")
                .role(Role.USER)
                .createdAt(LocalDateTime.now())
                .build());
    }

    private Order createOrder(
            User creator,
            CreatorRole creatorRole,
            OrderStatus status,
            PaymentStatus paymentStatus,
            SettlementStatus settlementStatus
    ) {
        Instant now = Instant.now();
        String unique = UUID.randomUUID().toString().replace("-", "");
        return orderRepository.save(Order.builder()
                .itemAmount(new BigDecimal("80.00"))
                .currency("NGN")
                .itemDescription("Webhook integration fixture")
                .creatorFirstName(creator.getFirstname())
                .creatorLastName(creator.getLastname())
                .creatorPhoneNumber(creator.getPhoneNumber())
                .creatorEmail(creator.getEmail())
                .creatorAddressLine1("1 Test Street")
                .creatorAddressLine2("")
                .creatorCity("Lagos")
                .creatorState("Lagos")
                .creatorCountry("NG")
                .creatorPostalCode("100001")
                .creatorRole(creatorRole)
                .status(status)
                .shareToken("share-" + unique)
                .creator(creator)
                .totalAmount(new BigDecimal("100.00"))
                .paymentReference("payment-" + unique)
                .paymentStatus(paymentStatus)
                .settlementStatus(settlementStatus)
                .fundedAt(paymentStatus == PaymentStatus.CONFIRMED ? now : null)
                .createdAt(LocalDateTime.now())
                .updatedAt(now)
                .build());
    }

    private void createVirtualAccount(User user, String accountNumber) {
        virtualAccountRepository.save(VirtualAccount.builder()
                .user(user)
                .accountNumber(accountNumber)
                .bankName("Test Bank")
                .accountName("TEST MEMBER")
                .createdAt(Instant.now())
                .build());
    }

    private void cleanDatabase() {
        processedWebhookRepository.deleteAllInBatch();
        settlementRepository.deleteAllInBatch();
        walletTransactionRepository.deleteAllInBatch();
        withdrawalRepository.deleteAllInBatch();
        receiptRepository.deleteAllInBatch();
        paymentAttemptRepository.deleteAllInBatch();
        quoteRepository.deleteAllInBatch();
        shipmentRepository.deleteAllInBatch();
        guestTokenRepository.deleteAllInBatch();
        parcelRepository.deleteAllInBatch();
        historyRepository.deleteAllInBatch();
        counterpartyRepository.deleteAllInBatch();
        otpChallengeRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        virtualAccountRepository.deleteAllInBatch();
        walletRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }
}
