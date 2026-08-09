package com.example.shopsecure.integration;

import com.example.shopsecure.ShopsecureApplication;
import com.example.shopsecure.entity.CreatorRole;
import com.example.shopsecure.entity.Order;
import com.example.shopsecure.entity.PaymentAttemptStatus;
import com.example.shopsecure.entity.OrderStatus;
import com.example.shopsecure.entity.Role;
import com.example.shopsecure.entity.ShippingQuote;
import com.example.shopsecure.entity.User;
import com.example.shopsecure.entity.VirtualAccount;
import com.example.shopsecure.entity.Wallet;
import com.example.shopsecure.provider.notification.SmsProvider;
import com.example.shopsecure.provider.payment.Bank;
import com.example.shopsecure.provider.payment.PaymentInitResponse;
import com.example.shopsecure.provider.payment.PaymentProvider;
import com.example.shopsecure.provider.payment.PaymentRequest;
import com.example.shopsecure.provider.payment.PaymentStatusUpdatedEvent;
import com.example.shopsecure.provider.payment.PaymentTransactionStatus;
import com.example.shopsecure.provider.payment.PaymentVerificationResult;
import com.example.shopsecure.provider.payment.PayoutProvider;
import com.example.shopsecure.provider.payment.PayoutResponse;
import com.example.shopsecure.provider.payment.PayoutStatus;
import com.example.shopsecure.provider.payment.ResolvedBankAccount;
import com.example.shopsecure.provider.payment.VirtualAccountRequest;
import com.example.shopsecure.provider.payment.VirtualAccountResponse;
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
import com.example.shopsecure.service.EmailService;
import com.example.shopsecure.service.OrderPaymentService;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = {
        ShopsecureApplication.class,
        TestConfig.class,
        OrderFlowIntegrationTest.ProviderTestConfig.class
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderFlowIntegrationTest {

    private static final String CREATOR_EMAIL = "creator@example.com";
    private static final String CREATOR_VIRTUAL_ACCOUNT = "1111111111";
    private static final String GUEST_PHONE = "2348012345678";
    private static final String TEST_BANK_CODE = "999";
    private static final String TEST_BANK_NAME = "Test Bank";
    private static final Pattern OTP_MESSAGE = Pattern.compile("\\b(\\d{6})\\b");

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderTestConfig {

        @Bean("orderFlowEmailService")
        @Primary
        EmailService orderFlowEmailService() {
            return mock(EmailService.class);
        }

        @Bean("orderFlowSmsProvider")
        @Primary
        SmsProvider orderFlowSmsProvider() {
            return mock(SmsProvider.class);
        }

        @Bean("orderFlowPaymentProvider")
        @Primary
        PaymentProvider orderFlowPaymentProvider() {
            return mock(PaymentProvider.class);
        }

        @Bean("orderFlowPayoutProvider")
        @Primary
        PayoutProvider orderFlowPayoutProvider() {
            return mock(PayoutProvider.class);
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private EmailService emailService;
    @Autowired
    private SmsProvider smsProvider;
    @Autowired
    private PaymentProvider paymentProvider;
    @Autowired
    private PayoutProvider payoutProvider;
    @Autowired
    private OrderPaymentService paymentService;
    @Autowired
    private PasswordEncoder passwordEncoder;
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
    private PaymentReceiptRepository paymentReceiptRepository;
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

    private final AtomicReference<String> deliveredOtp = new AtomicReference<>();
    private final AtomicReference<String> deliveredEmailOtp = new AtomicReference<>();
    private User creator;
    private User otherUser;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        reset(emailService, smsProvider, paymentProvider, payoutProvider);
        deliveredOtp.set(null);
        deliveredEmailOtp.set(null);

        doAnswer(invocation -> {
            String message = invocation.getArgument(1, String.class);
            Matcher matcher = OTP_MESSAGE.matcher(message);
            if (matcher.find()) {
                deliveredOtp.set(matcher.group(1));
            }
            return null;
        }).when(smsProvider).sendMessage(anyString(), anyString());
        doAnswer(invocation -> {
            String html = invocation.getArgument(2, String.class);
            Matcher matcher = OTP_MESSAGE.matcher(html);
            if (matcher.find()) {
                deliveredEmailOtp.set(matcher.group(1));
            }
            return null;
        }).when(emailService).sendEmail(anyString(), anyString(), anyString());

        when(paymentProvider.createVirtualAccount(any(VirtualAccountRequest.class)))
                .thenReturn(new VirtualAccountResponse(
                        "9999999999",
                        "Wema Bank",
                        "NEW MEMBER",
                        null
                ));
        when(paymentProvider.initializeTransaction(any(PaymentRequest.class))).thenAnswer(invocation -> {
            PaymentRequest request = invocation.getArgument(0, PaymentRequest.class);
            return new PaymentInitResponse(
                    "https://checkout.paystack.test/" + request.reference(),
                    "access-" + request.reference(),
                    request.reference()
            );
        });
        when(payoutProvider.listBanks()).thenReturn(List.of(new Bank(TEST_BANK_NAME, TEST_BANK_CODE)));
        when(payoutProvider.resolveBankAccount(any())).thenAnswer(invocation -> {
            var request = invocation.getArgument(0, com.example.shopsecure.provider.payment.BankAccountLookupRequest.class);
            return new ResolvedBankAccount(
                    "Bola Vendor",
                    request.accountNumber(),
                    request.bankCode(),
                    TEST_BANK_NAME
            );
        });
        when(payoutProvider.createPayoutRecipient(any()))
                .thenReturn(new com.example.shopsecure.provider.payment.PayoutRecipientResponse("RCP_TEST_VENDOR"));
        when(payoutProvider.initiatePayout(any())).thenAnswer(invocation -> {
            var request = invocation.getArgument(0, com.example.shopsecure.provider.payment.PayoutRequest.class);
            return new PayoutResponse("TRF_TEST", request.reference(), PayoutStatus.PENDING);
        });

        creator = createUser(CREATOR_EMAIL, "2348098765432", "Ada", "Creator");
        otherUser = createUser("other-user@example.com", "2348077777777", "Eve", "Other");
        createVirtualAccount(creator, CREATOR_VIRTUAL_ACCOUNT, "ADA CREATOR");
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void buyerCreatedOrderUsesWalletTopUpAndExplicitWalletPayment() throws Exception {
        JsonNode created = createOrder(CreatorRole.BUYER, null);
        long orderId = created.path("id").asLong();
        String shareToken = created.path("shareToken").asText();

        JsonNode initialPublicOrder = getPublicOrder(shareToken);
        assertThat(initialPublicOrder.path("status").asText()).isEqualTo("AWAITING_COUNTERPARTY");
        assertThat(initialPublicOrder.path("creatorRole").asText()).isEqualTo("BUYER");
        assertThat(initialPublicOrder.path("counterpartyRole").asText()).isEqualTo("VENDOR");

        VerifiedGuest vendor = submitAndVerifyGuest(
                shareToken,
                vendorCounterpartyJson(true),
                CreatorRole.VENDOR
        );
        verify(payoutProvider).createPayoutRecipient(argThat(request ->
                request.accountName().equals("Bola Vendor")
                        && request.accountNumber().equals("0123456789")
                        && request.bankCode().equals(TEST_BANK_CODE)
        ));

        JsonNode guestStatusBeforeSelection = getGuestStatus(shareToken, vendor.accessToken());
        assertThat(guestStatusBeforeSelection.path("status").asText()).isEqualTo("AWAITING_SHIPPING_RATE");
        assertThat(guestStatusBeforeSelection.hasNonNull("paymentInstructions")).isFalse();
        assertThat(guestStatusBeforeSelection.toString()).doesNotContain("virtualAccount");

        JsonNode quotes = responseJson(mockMvc.perform(post("/orders/{orderId}/shipping/quotes", orderId)
                        .with(user(creator.getEmail()).roles("USER")))
                .andExpect(status().isOk()));
        assertThat(quotes.size()).isEqualTo(2);
        JsonNode standardQuote = quotes.get(0);
        assertMoney(standardQuote, "/deliveryFee", "5750.00");
        assertMoney(standardQuote, "/serviceFee", "500.00");
        assertMoney(standardQuote, "/totalAmount", "131250.00");

        long quoteId = standardQuote.path("quoteId").asLong();
        ShippingQuote persistedQuote = quoteRepository.findById(quoteId).orElseThrow();
        assertThat(persistedQuote.getProviderAmount()).isEqualByComparingTo("4750.00");
        assertThat(persistedQuote.getDeliveryMarkup()).isEqualByComparingTo("1000.00");
        assertThat(persistedQuote.getDeliveryFee()).isEqualByComparingTo("5750.00");

        JsonNode selection = responseJson(mockMvc.perform(put("/orders/{orderId}/shipping/selection", orderId)
                        .with(user(creator.getEmail()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quoteId\":" + quoteId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_PAYMENT")));
        assertPricing(selection.path("pricing"), "5750.00", "131250.00");
        assertThat(selection.path("fundingMode").asText()).isEqualTo("WALLET");
        assertThat(selection.hasNonNull("paymentInstructions")).isFalse();

        JsonNode creatorDetails = responseJson(mockMvc.perform(get("/orders/{orderId}", orderId)
                        .with(user(creator.getEmail()).roles("USER")))
                .andExpect(status().isOk()));
        assertThat(creatorDetails.path("fundingMode").asText()).isEqualTo("WALLET");
        assertThat(creatorDetails.hasNonNull("paymentInstructions")).isFalse();
        mockMvc.perform(post("/orders/token/{shareToken}/payments/checkout", shareToken)
                        .header("X-Order-Access-Token", vendor.accessToken()))
                .andExpect(status().isForbidden());

        String idempotencyKey = "buyer-wallet-payment";
        mockMvc.perform(post("/orders/{orderId}/payments/wallet", orderId)
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
        mockMvc.perform(post("/orders/{orderId}/payments/wallet", orderId)
                        .with(user(otherUser.getEmail()).roles("USER"))
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/orders/{orderId}/payments/wallet", orderId)
                        .with(user(creator.getEmail()).roles("USER")))
                .andExpect(status().isBadRequest());

        JsonNode insufficient = responseJson(mockMvc.perform(post(
                        "/orders/{orderId}/payments/wallet",
                        orderId
                )
                        .with(user(creator.getEmail()).roles("USER"))
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("INSUFFICIENT_FUNDS")));
        assertMoney(insufficient, "/amountRequired", "131250.00");
        assertMoney(insufficient, "/walletBalance", "0.00");
        assertMoney(insufficient, "/shortfall", "131250.00");
        assertPaymentAccount(insufficient.path("topUpInstructions"), "131250.00");
        assertThat(walletTransactionRepository.count()).isZero();

        paymentService.handleEvent(new PaymentStatusUpdatedEvent(
                "buyer-wallet-top-up",
                "buyer-wallet-top-up-reference",
                PaymentTransactionStatus.SUCCESS,
                new BigDecimal("131250.00"),
                "NGN",
                "dedicated_nuban",
                CREATOR_VIRTUAL_ACCOUNT,
                Instant.now(),
                true
        ));

        Order stillAwaitingPayment = orderRepository.findById(orderId).orElseThrow();
        assertThat(stillAwaitingPayment.getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        assertThat(walletRepository.findByUser_Id(creator.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("131250.00");
        assertThat(walletTransactionRepository.findAll())
                .singleElement()
                .satisfies(transaction -> {
                    assertThat(transaction.getTransactionType().name()).isEqualTo("DVA_CREDIT");
                    assertThat(transaction.getOrder()).isNull();
                });

        JsonNode funded = responseJson(mockMvc.perform(post(
                        "/orders/{orderId}/payments/wallet",
                        orderId
                )
                        .with(user(creator.getEmail()).roles("USER"))
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("FUNDED"))
                .andExpect(jsonPath("$.paymentStatus").value("CONFIRMED")));
        assertMoney(funded, "/walletBalance", "0.00");
        assertThat(funded.hasNonNull("topUpInstructions")).isFalse();
        mockMvc.perform(post("/orders/{orderId}/payments/wallet", orderId)
                        .with(user(creator.getEmail()).roles("USER"))
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("FUNDED"));
        assertThat(walletTransactionRepository.findAll()).hasSize(2);
        assertThat(walletTransactionRepository
                .findByOrder_IdAndTransactionType(
                        orderId,
                        com.example.shopsecure.entity.WalletTransactionType.ORDER_PAYMENT_DEBIT
                )).isPresent();
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.READY_FOR_DELIVERY);

        JsonNode finalPublicOrder = getPublicOrder(shareToken);
        assertThat(finalPublicOrder.path("status").asText()).isEqualTo("READY_FOR_DELIVERY");
        JsonNode vendorStatus = getGuestStatus(shareToken, vendor.accessToken());
        assertThat(vendorStatus.path("status").asText()).isEqualTo("READY_FOR_DELIVERY");
        assertThat(vendorStatus.hasNonNull("paymentInstructions")).isFalse();
        assertThat(vendorStatus.toString()).doesNotContain("virtualAccount");

        Order selectedOrder = orderRepository.findById(orderId).orElseThrow();
        assertThat(selectedOrder.getStatus()).isEqualTo(OrderStatus.READY_FOR_DELIVERY);
        assertThat(selectedOrder.getProviderDeliveryFee()).isEqualByComparingTo("4750.00");
        assertThat(selectedOrder.getDeliveryMarkup()).isEqualByComparingTo("1000.00");
        assertThat(selectedOrder.getDeliveryFee()).isEqualByComparingTo("5750.00");
        assertThat(selectedOrder.getServiceFee()).isEqualByComparingTo("500.00");
        assertThat(selectedOrder.getTotalAmount()).isEqualByComparingTo("131250.00");
        verify(emailService, times(1)).sendEmail(
                eq("bola.vendor@example.com"),
                eq("Buyer payment confirmed - prepare for pickup"),
                argThat(html -> html.contains("The buyer has paid for the order")
                        && html.contains("NGN 131,250.00")
                        && html.contains("delivery partner will contact you"))
        );
        verify(smsProvider, times(1)).sendMessage(
                eq(GUEST_PHONE),
                argThat(message -> message.contains("Buyer payment is confirmed")
                        && message.contains("delivery partner will contact you"))
        );
        verify(emailService, times(1)).sendEmail(
                eq(CREATOR_EMAIL),
                eq("Order created and payment confirmed - pickup is being arranged"),
                argThat(html -> html.contains("Your order has been created")
                        && html.contains("vendor has been notified")
                        && html.contains("NGN 131,250.00"))
        );
        verify(smsProvider, times(1)).sendMessage(
                eq(creator.getPhoneNumber()),
                argThat(message -> message.contains("Your order has been created")
                        && message.contains("vendor has been notified"))
        );
        verify(payoutProvider, never()).initiatePayout(any());
    }

    @Test
    void vendorCreatedOrderReplaysActiveCheckoutAndReplacesVerifiedFailure() throws Exception {
        JsonNode created = createOrder(CreatorRole.VENDOR, parcelJson("3.000"));
        long orderId = created.path("id").asLong();
        String shareToken = created.path("shareToken").asText();
        assertThat(created.path("fundingMode").asText()).isEqualTo("PAYSTACK_CHECKOUT");

        getPublicOrder(shareToken);
        String creatorDetailsBeforeVerification = mockMvc.perform(get("/orders/{orderId}", orderId)
                        .with(user(creator.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(creatorDetailsBeforeVerification).doesNotContain("virtualAccount");

        mockMvc.perform(post("/orders/token/{shareToken}/counterparty-info", shareToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buyerCounterpartyJson(true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("A buyer must not submit payout bank or parcel details"));
        assertThat(counterpartyRepository.count()).isZero();
        assertThat(deliveredOtp.get()).isNull();

        VerifiedGuest buyer = submitAndVerifyGuest(
                shareToken,
                buyerCounterpartyJson(false),
                CreatorRole.BUYER
        );
        getPublicOrder(shareToken);

        JsonNode quotes = responseJson(mockMvc.perform(post("/orders/token/{shareToken}/shipping/quotes", shareToken)
                        .header("X-Order-Access-Token", buyer.accessToken()))
                .andExpect(status().isOk()));
        assertThat(quotes.size()).isEqualTo(2);
        JsonNode standardQuote = quotes.get(0);
        assertMoney(standardQuote, "/deliveryFee", "6000.00");
        assertMoney(standardQuote, "/serviceFee", "500.00");
        assertMoney(standardQuote, "/totalAmount", "131500.00");

        long quoteId = standardQuote.path("quoteId").asLong();
        ShippingQuote persistedQuote = quoteRepository.findById(quoteId).orElseThrow();
        assertThat(persistedQuote.getProviderAmount()).isEqualByComparingTo("5000.00");
        assertThat(persistedQuote.getDeliveryMarkup()).isEqualByComparingTo("1000.00");

        JsonNode selection = responseJson(mockMvc.perform(
                        put("/orders/token/{shareToken}/shipping/selection", shareToken)
                                .header("X-Order-Access-Token", buyer.accessToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"quoteId\":" + quoteId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_PAYMENT")));
        assertPricing(selection.path("pricing"), "6000.00", "131500.00");
        assertThat(selection.path("fundingMode").asText()).isEqualTo("PAYSTACK_CHECKOUT");
        assertThat(selection.hasNonNull("paymentInstructions")).isFalse();

        JsonNode buyerStatus = getGuestStatus(shareToken, buyer.accessToken());
        assertThat(buyerStatus.path("status").asText()).isEqualTo("AWAITING_PAYMENT");
        assertThat(buyerStatus.path("fundingMode").asText()).isEqualTo("PAYSTACK_CHECKOUT");
        assertMoney(buyerStatus, "/amountExpected", "131500.00");
        assertThat(buyerStatus.hasNonNull("paymentInstructions")).isFalse();

        mockMvc.perform(post("/orders/token/{shareToken}/payments/checkout", shareToken))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/orders/token/{shareToken}/payments/checkout", shareToken)
                        .header("X-Order-Access-Token", "wrong-token"))
                .andExpect(status().isForbidden());

        JsonNode checkout = responseJson(mockMvc.perform(
                        post("/orders/token/{shareToken}/payments/checkout", shareToken)
                                .header("X-Order-Access-Token", buyer.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fundingMode").value("PAYSTACK_CHECKOUT"))
                .andExpect(jsonPath("$.paymentStatus").value("PENDING")));
        assertMoney(checkout, "/amount", "131500.00");
        assertThat(checkout.path("reference").asText()).startsWith("checkout-order-" + orderId + "-");
        assertThat(checkout.path("authorizationUrl").asText())
                .isEqualTo("https://checkout.paystack.test/" + checkout.path("reference").asText());

        JsonNode replay = responseJson(mockMvc.perform(
                        post("/orders/token/{shareToken}/payments/checkout", shareToken)
                                .header("X-Order-Access-Token", buyer.accessToken()))
                .andExpect(status().isOk()));
        assertThat(replay.path("reference").asText()).isEqualTo(checkout.path("reference").asText());
        assertThat(paymentAttemptRepository.count()).isOne();

        var staleAttempt = paymentAttemptRepository.findAll().get(0);
        staleAttempt.setUpdatedAt(Instant.now().minus(Duration.ofHours(1)));
        paymentAttemptRepository.saveAndFlush(staleAttempt);
        when(paymentProvider.verifyTransaction(staleAttempt.getProviderReference()))
                .thenReturn(new PaymentVerificationResult(
                        null,
                        staleAttempt.getProviderReference(),
                        PaymentTransactionStatus.PENDING,
                        staleAttempt.getAmount(),
                        staleAttempt.getCurrency(),
                        null,
                        null
                ));

        JsonNode verifiedPending = responseJson(mockMvc.perform(
                        post("/orders/token/{shareToken}/payments/checkout", shareToken)
                                .header("X-Order-Access-Token", buyer.accessToken()))
                .andExpect(status().isOk()));
        assertThat(verifiedPending.path("reference").asText())
                .isEqualTo(checkout.path("reference").asText());
        mockMvc.perform(post("/orders/token/{shareToken}/payments/checkout", shareToken)
                        .header("X-Order-Access-Token", buyer.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").value(checkout.path("reference").asText()));
        verify(paymentProvider).verifyTransaction(staleAttempt.getProviderReference());

        staleAttempt = paymentAttemptRepository.findById(staleAttempt.getId()).orElseThrow();
        staleAttempt.setUpdatedAt(Instant.now().minus(Duration.ofHours(1)));
        paymentAttemptRepository.saveAndFlush(staleAttempt);
        when(paymentProvider.verifyTransaction(staleAttempt.getProviderReference()))
                .thenReturn(new PaymentVerificationResult(
                        null,
                        staleAttempt.getProviderReference(),
                        PaymentTransactionStatus.FAILED,
                        staleAttempt.getAmount(),
                        staleAttempt.getCurrency(),
                        null,
                        null
                ));

        JsonNode replacement = responseJson(mockMvc.perform(
                        post("/orders/token/{shareToken}/payments/checkout", shareToken)
                                .header("X-Order-Access-Token", buyer.accessToken()))
                .andExpect(status().isOk()));
        assertThat(replacement.path("reference").asText())
                .isNotEqualTo(checkout.path("reference").asText());
        assertThat(paymentAttemptRepository.findAll())
                .hasSize(2)
                .anySatisfy(attempt -> {
                    assertThat(attempt.getProviderReference()).isEqualTo(checkout.path("reference").asText());
                    assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
                })
                .anySatisfy(attempt -> {
                    assertThat(attempt.getProviderReference()).isEqualTo(replacement.path("reference").asText());
                    assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.INITIALIZED);
                });
        verify(paymentProvider, times(2)).verifyTransaction(staleAttempt.getProviderReference());
        verify(paymentProvider, times(2)).initializeTransaction(argThat(request ->
                request.amount().compareTo(new BigDecimal("131500.00")) == 0
                        && request.customerEmail().equals("bisi.buyer@example.com")
                        && request.currency().equals("NGN")
                        && request.callbackUrl().endsWith("/orders/token/" + shareToken)
                        && request.metadata().get("order_id").equals(orderId)
        ));

        mockMvc.perform(post("/orders/{orderId}/payments/wallet", orderId)
                        .with(user(creator.getEmail()).roles("USER"))
                        .header("Idempotency-Key", "direct-order-wallet-attempt"))
                .andExpect(status().isForbidden());

        String creatorDetailsAfterSelection = mockMvc.perform(get("/orders/{orderId}", orderId)
                        .with(user(creator.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(creatorDetailsAfterSelection).doesNotContain("virtualAccount");
        JsonNode finalPublicOrder = getPublicOrder(shareToken);
        assertThat(finalPublicOrder.path("status").asText()).isEqualTo("AWAITING_PAYMENT");

        Order selectedOrder = orderRepository.findById(orderId).orElseThrow();
        assertThat(selectedOrder.getFundingMode().name()).isEqualTo("PAYSTACK_CHECKOUT");
        assertThat(selectedOrder.getPaymentReference()).isNull();
        assertThat(selectedOrder.getProviderDeliveryFee()).isEqualByComparingTo("5000.00");
        assertThat(selectedOrder.getDeliveryMarkup()).isEqualByComparingTo("1000.00");
        assertThat(selectedOrder.getDeliveryFee()).isEqualByComparingTo("6000.00");
        assertThat(selectedOrder.getServiceFee()).isEqualByComparingTo("500.00");
        assertThat(selectedOrder.getTotalAmount()).isEqualByComparingTo("131500.00");
        verify(payoutProvider, never()).listBanks();
        verify(payoutProvider, never()).resolveBankAccount(any());
        verify(payoutProvider, never()).createPayoutRecipient(any());
        verify(payoutProvider, never()).initiatePayout(any());
    }

    @Test
    void registrationRequiresEmailVerificationBeforeProvisioningAndLogin() throws Exception {
        long walletsBefore = walletRepository.count();
        long virtualAccountsBefore = virtualAccountRepository.count();

        JsonNode challenge = responseJson(mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstname":"New",
                                  "lastname":"Member",
                                  "email":"new-member@example.com",
                                  "phoneNumber":"2348066666666",
                                  "password":"Secure1!"
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.challengeId").isNotEmpty())
                .andExpect(jsonPath("$.emailMasked").value("n***@example.com")));

        User registered = userRepository.findByEmail("new-member@example.com").orElseThrow();
        assertThat(registered.getEmailVerifiedAt()).isNull();
        assertThat(walletRepository.count()).isEqualTo(walletsBefore);
        assertThat(virtualAccountRepository.count()).isEqualTo(virtualAccountsBefore);
        verify(paymentProvider, never()).createVirtualAccount(any(VirtualAccountRequest.class));
        verify(emailService).sendEmail(
                eq("new-member@example.com"),
                eq("Verify your ShopSecure email"),
                argThat(html -> html.contains(deliveredEmailOtp.get()))
        );

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"NEW-MEMBER@example.com","password":"Secure1!"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Verify your email before signing in"));

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"NEW-MEMBER@example.com",
                                  "challengeId":"%s",
                                  "code":"%s"
                                }
                                """.formatted(challenge.path("challengeId").asText(), deliveredEmailOtp.get())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.virtualAccount.accountNumber").value("9999999999"))
                .andExpect(jsonPath("$.virtualAccount.bankName").value("Wema Bank"));

        User verifiedUser = userRepository.findByEmail("new-member@example.com").orElseThrow();
        assertThat(verifiedUser.getEmailVerifiedAt()).isNotNull();
        assertThat(walletRepository.count()).isEqualTo(walletsBefore + 1);
        assertThat(virtualAccountRepository.count()).isEqualTo(virtualAccountsBefore + 1);
        assertThat(walletRepository.findByUser_Id(verifiedUser.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("0.00");
        assertThat(virtualAccountRepository.findByUser_Id(verifiedUser.getId()).orElseThrow().getAccountNumber())
                .isEqualTo("9999999999");
        verify(paymentProvider).createVirtualAccount(argThat(request ->
                request.customerName().equals("New Member")
                        && request.email().equals("new-member@example.com")
                        && request.phone().equals("2348066666666")
                        && request.customerReference().equals("user-" + verifiedUser.getId())
        ));
    }

    @Test
    void forgotPasswordIsNonEnumeratingAndResetRevokesExistingJwt() throws Exception {
        JsonNode login = responseJson(mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"CREATOR@example.com","password":"Secure1!"}
                                """))
                .andExpect(status().isOk()));
        String oldToken = login.path("token").asText();

        String genericMessage = "If an account exists for that email, a password reset code has been sent.";
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"missing@example.com\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(genericMessage));
        verify(emailService, never()).sendEmail(eq("missing@example.com"), anyString(), anyString());

        deliveredEmailOtp.set(null);
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"CREATOR@example.com\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(genericMessage));
        assertThat(deliveredEmailOtp.get()).matches("\\d{6}");
        verify(emailService).sendEmail(
                eq(CREATOR_EMAIL),
                eq("Reset your ShopSecure password"),
                argThat(html -> html.contains(deliveredEmailOtp.get()))
        );

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"creator@example.com","code":"000000","newPassword":"NewSecure2!"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid or expired verification code"));

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"creator@example.com","code":"%s","newPassword":"NewSecure2!"}
                                """.formatted(deliveredEmailOtp.get())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password reset successful. Sign in with your new password."));

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + oldToken))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"creator@example.com","password":"Secure1!"}
                                """))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"creator@example.com","password":"NewSecure2!"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void existingUserWithoutPhoneOrDvaCanPatchProfileAndReceiveDva() throws Exception {
        User incompleteUser = createUser(
                "existing-member@example.com",
                null,
                "Existing",
                "Member"
        );
        assertThat(virtualAccountRepository.findByUser_Id(incompleteUser.getId())).isEmpty();

        mockMvc.perform(patch("/api/users/me")
                        .with(user(incompleteUser.getEmail()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstname":"Updated",
                                  "lastname":"Member",
                                  "phoneNumber":"2348055555555"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(incompleteUser.getId()))
                .andExpect(jsonPath("$.firstname").value("Updated"))
                .andExpect(jsonPath("$.phoneNumber").value("2348055555555"))
                .andExpect(jsonPath("$.virtualAccount.accountNumber").value("9999999999"))
                .andExpect(jsonPath("$.virtualAccount.bankName").value("Wema Bank"));

        User updated = userRepository.findById(incompleteUser.getId()).orElseThrow();
        VirtualAccount account = virtualAccountRepository.findByUser_Id(incompleteUser.getId()).orElseThrow();
        assertThat(updated.getPhoneNumber()).isEqualTo("2348055555555");
        assertThat(account.getAccountNumber()).isEqualTo("9999999999");
        verify(paymentProvider).createVirtualAccount(argThat(request ->
                request.customerName().equals("Updated Member")
                        && request.email().equals("existing-member@example.com")
                        && request.phone().equals("2348055555555")
                        && request.customerReference().equals("user-" + incompleteUser.getId())
        ));
    }

    @Test
    void counterpartyResubmissionInvalidatesPriorOtpAndOnlyLatestChallengeVerifies() throws Exception {
        JsonNode created = createOrder(CreatorRole.VENDOR, parcelJson("3.000"));
        long orderId = created.path("id").asLong();
        String shareToken = created.path("shareToken").asText();

        JsonNode firstChallenge = responseJson(mockMvc.perform(
                        post("/orders/token/{shareToken}/counterparty-info", shareToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(buyerCounterpartyJson(false)))
                .andExpect(status().isAccepted()));
        String firstCode = deliveredOtp.get();
        var priorChallenge = otpChallengeRepository
                .findByPublicId(firstChallenge.path("challengeId").asText())
                .orElseThrow();
        priorChallenge.setCreatedAt(LocalDateTime.now().minusMinutes(2));
        otpChallengeRepository.saveAndFlush(priorChallenge);

        String updatedPhone = "2348088888888";
        String updatedEmail = "updated.buyer@example.com";
        deliveredOtp.set(null);
        JsonNode latestChallenge = responseJson(mockMvc.perform(
                        post("/orders/token/{shareToken}/counterparty-info", shareToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(buyerCounterpartyJson(false)
                                        .replace(GUEST_PHONE, updatedPhone)
                                        .replace("bisi.buyer@example.com", updatedEmail)))
                .andExpect(status().isAccepted()));
        String latestCode = deliveredOtp.get();

        assertThat(otpChallengeRepository.findByPublicId(firstChallenge.path("challengeId").asText())
                .orElseThrow().getInvalidatedAt()).isNotNull();
        int staleVerificationStatus = mockMvc.perform(post("/orders/token/{shareToken}/verify-otp", shareToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"challengeId":"%s","code":"%s"}
                                """.formatted(firstChallenge.path("challengeId").asText(), firstCode)))
                .andReturn().getResponse().getStatus();
        assertThat(counterpartyRepository.findByOrder_Id(orderId).orElseThrow().isPhoneVerified()).isFalse();

        mockMvc.perform(post("/orders/token/{shareToken}/verify-otp", shareToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"challengeId":"%s","code":"%s"}
                                """.formatted(latestChallenge.path("challengeId").asText(), latestCode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_SHIPPING_RATE"))
                .andExpect(jsonPath("$.guestAccessToken").isNotEmpty());

        var counterparty = counterpartyRepository.findByOrder_Id(orderId).orElseThrow();
        assertThat(counterparty.getPhoneNumber()).isEqualTo(updatedPhone);
        assertThat(counterparty.getEmail()).isEqualTo(updatedEmail);
        assertThat(counterparty.isPhoneVerified()).isTrue();
        assertThat(otpChallengeRepository.findByPublicId(firstChallenge.path("challengeId").asText())
                .orElseThrow().getConsumedAt()).isNull();
        assertThat(otpChallengeRepository.findByPublicId(latestChallenge.path("challengeId").asText())
                .orElseThrow().getConsumedAt()).isNotNull();
        assertThat(staleVerificationStatus)
                .as("an invalidated OTP challenge should be rejected as a client error")
                .isEqualTo(400);
    }

    @Test
    void guestCanRecoverAccessAfterRevocationAndExpiryWhilePriorTokensRemainInvalid() throws Exception {
        JsonNode created = createOrder(CreatorRole.VENDOR, parcelJson("3.000"));
        long orderId = created.path("id").asLong();
        String shareToken = created.path("shareToken").asText();
        VerifiedGuest original = submitAndVerifyGuest(
                shareToken,
                buyerCounterpartyJson(false),
                CreatorRole.BUYER
        );

        var originalToken = guestTokenRepository.findByOrder_IdAndRevokedAtIsNull(orderId)
                .stream().findFirst().orElseThrow();
        originalToken.setRevokedAt(Instant.now());
        guestTokenRepository.saveAndFlush(originalToken);
        assertGuestAccessForbidden(shareToken, original.accessToken());

        JsonNode revokedRecoveryChallenge = requestAccessRecovery(shareToken);
        String revokedRecoveryCode = deliveredOtp.get();
        assertRecoveryOtpDelivered("2348012345678", "bisi.buyer@example.com", revokedRecoveryCode);
        String recoveredToken = recoverAccess(
                shareToken,
                revokedRecoveryChallenge.path("challengeId").asText(),
                revokedRecoveryCode
        );
        assertThat(recoveredToken).isNotEqualTo(original.accessToken());
        getGuestStatus(shareToken, recoveredToken);
        assertGuestAccessForbidden(shareToken, original.accessToken());

        var expiringToken = guestTokenRepository.findByOrder_IdAndRevokedAtIsNull(orderId)
                .stream().findFirst().orElseThrow();
        expiringToken.setExpiresAt(Instant.now().minusSeconds(1));
        guestTokenRepository.saveAndFlush(expiringToken);
        assertGuestAccessForbidden(shareToken, recoveredToken);

        JsonNode expiredRecoveryChallenge = requestAccessRecovery(shareToken);
        String expiredRecoveryCode = deliveredOtp.get();
        assertRecoveryOtpDelivered("2348012345678", "bisi.buyer@example.com", expiredRecoveryCode);
        String latestToken = recoverAccess(
                shareToken,
                expiredRecoveryChallenge.path("challengeId").asText(),
                expiredRecoveryCode
        );

        assertThat(latestToken).isNotIn(original.accessToken(), recoveredToken);
        getGuestStatus(shareToken, latestToken);
        assertGuestAccessForbidden(shareToken, original.accessToken());
        assertGuestAccessForbidden(shareToken, recoveredToken);
        assertThat(guestTokenRepository.count()).isEqualTo(3);
        assertThat(guestTokenRepository.findByOrder_IdAndRevokedAtIsNull(orderId)).hasSize(1);
    }

    @Test
    void verifiedCheckoutPaymentFundsOrderAndDeduplicatesTheLaterWebhook() throws Exception {
        JsonNode created = createOrder(CreatorRole.VENDOR, parcelJson("3.000"));
        long orderId = created.path("id").asLong();
        String shareToken = created.path("shareToken").asText();
        VerifiedGuest buyer = submitAndVerifyGuest(
                shareToken,
                buyerCounterpartyJson(false),
                CreatorRole.BUYER
        );
        JsonNode quotes = responseJson(mockMvc.perform(
                        post("/orders/token/{shareToken}/shipping/quotes", shareToken)
                                .header("X-Order-Access-Token", buyer.accessToken()))
                .andExpect(status().isOk()));
        JsonNode selection = responseJson(mockMvc.perform(
                        put("/orders/token/{shareToken}/shipping/selection", shareToken)
                                .header("X-Order-Access-Token", buyer.accessToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"quoteId\":" + quotes.get(0).path("quoteId").asLong() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_PAYMENT")));
        assertThat(selection.path("fundingMode").asText()).isEqualTo("PAYSTACK_CHECKOUT");
        assertThat(selection.hasNonNull("paymentInstructions")).isFalse();
        assertThat(getGuestStatus(shareToken, buyer.accessToken()).hasNonNull("paymentInstructions"))
                .isFalse();

        JsonNode checkout = responseJson(mockMvc.perform(
                        post("/orders/token/{shareToken}/payments/checkout", shareToken)
                                .header("X-Order-Access-Token", buyer.accessToken()))
                .andExpect(status().isOk()));

        String reference = checkout.path("reference").asText();
        String eventId = "guest-checkout-funded";
        Instant paidAt = Instant.now();
        var checkoutAttempt = paymentAttemptRepository.findByProviderReference(reference).orElseThrow();
        checkoutAttempt.setUpdatedAt(Instant.now().minus(Duration.ofHours(1)));
        paymentAttemptRepository.saveAndFlush(checkoutAttempt);
        when(paymentProvider.verifyTransaction(reference)).thenReturn(new PaymentVerificationResult(
                eventId,
                reference,
                PaymentTransactionStatus.SUCCESS,
                new BigDecimal("131500.00"),
                "NGN",
                "card",
                paidAt
        ));

        mockMvc.perform(post("/orders/token/{shareToken}/payments/checkout", shareToken)
                        .header("X-Order-Access-Token", buyer.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("CONFIRMED"));

        paymentService.handleEvent(new PaymentStatusUpdatedEvent(
                eventId,
                reference,
                PaymentTransactionStatus.SUCCESS,
                new BigDecimal("131500.00"),
                "NGN",
                "card",
                null,
                paidAt,
                true
        ));

        JsonNode fundedStatus = getGuestStatus(shareToken, buyer.accessToken());
        assertThat(fundedStatus.path("status").asText()).isEqualTo("READY_FOR_DELIVERY");
        assertThat(fundedStatus.hasNonNull("paymentInstructions")).isFalse();
        assertThat(fundedStatus.toString()).doesNotContain(CREATOR_VIRTUAL_ACCOUNT);
        mockMvc.perform(post("/orders/token/{shareToken}/payments/checkout", shareToken)
                        .header("X-Order-Access-Token", buyer.accessToken()))
                .andExpect(status().isBadRequest());
        assertThat(paymentAttemptRepository.findAll())
                .singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.getOrder().getId()).isEqualTo(orderId);
                    assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS);
                });
        assertThat(paymentReceiptRepository.count()).isOne();
        verify(emailService, times(1)).sendEmail(
                eq(CREATOR_EMAIL),
                eq("Buyer payment confirmed - prepare for pickup"),
                argThat(html -> html.contains("The buyer has paid for the order")
                        && html.contains("NGN 131,500.00")
                        && html.contains("delivery partner will contact you"))
        );
        verify(smsProvider, times(1)).sendMessage(
                eq(creator.getPhoneNumber()),
                argThat(message -> message.contains("Buyer payment is confirmed")
                        && message.contains("delivery partner will contact you"))
        );
        verify(emailService, times(1)).sendEmail(
                eq("bisi.buyer@example.com"),
                eq("Order created and payment confirmed - pickup is being arranged"),
                argThat(html -> html.contains("Your order has been created")
                        && html.contains("vendor has been notified")
                        && html.contains("NGN 131,500.00"))
        );
        verify(smsProvider, times(1)).sendMessage(
                eq(GUEST_PHONE),
                argThat(message -> message.contains("Your order has been created")
                        && message.contains("vendor has been notified"))
        );
    }

    @Test
    void publicVerificationIssuesTokenWhileGuestAndCreatorDetailsRemainProtected() throws Exception {
        JsonNode created = createOrder(CreatorRole.BUYER, null);
        long orderId = created.path("id").asLong();
        String shareToken = created.path("shareToken").asText();

        VerifiedGuest vendor = submitAndVerifyGuest(
                shareToken,
                vendorCounterpartyJson(true),
                CreatorRole.VENDOR
        );

        mockMvc.perform(get("/orders/token/{shareToken}/status", shareToken))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isBetween(400, 499));
        mockMvc.perform(get("/orders/token/{shareToken}/status", shareToken)
                        .header("X-Order-Access-Token", "wrong-guest-token"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/orders/{orderId}", orderId)
                        .with(user(otherUser.getEmail()).roles("USER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/orders/token/{shareToken}/status", shareToken)
                        .header("X-Order-Access-Token", vendor.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_SHIPPING_RATE"));
    }

    @Test
    void sellerParcelRulesAndIsoCountryAndPostalCodeAreValidated() throws Exception {
        mockMvc.perform(post("/orders")
                        .with(user(creator.getEmail()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(CreatorRole.VENDOR, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("A vendor creator must provide parcel details"));

        mockMvc.perform(post("/orders")
                        .with(user(creator.getEmail()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(CreatorRole.BUYER, parcelJson("2.000"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("The vendor must provide parcel details"));

        JsonNode addressErrors = responseJson(mockMvc.perform(post("/orders")
                        .with(user(creator.getEmail()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidAddressOrderJson()))
                .andExpect(status().isBadRequest()));
        assertThat(addressErrors.path("details").path("creatorAddress.country").asText())
                .isEqualTo("Country must be an ISO-2 country code");
        assertThat(addressErrors.path("details").path("creatorAddress.postalCode").asText())
                .isEqualTo("Postal code is required");

        JsonNode buyerOrder = createOrder(CreatorRole.BUYER, null);
        mockMvc.perform(post("/orders/token/{shareToken}/counterparty-info", buyerOrder.path("shareToken").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(vendorCounterpartyJson(false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Vendor parcel details are required"));
        assertThat(deliveredOtp.get()).isNull();
    }

    private JsonNode createOrder(CreatorRole creatorRole, String parcel) throws Exception {
        return responseJson(mockMvc.perform(post("/orders")
                        .with(user(creator.getEmail()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(creatorRole, parcel)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AWAITING_COUNTERPARTY"))
                .andExpect(jsonPath("$.creatorRole").value(creatorRole.name()))
                .andExpect(jsonPath("$.counterpartyRole").value(creatorRole.opposite().name()))
                .andExpect(jsonPath("$.shareToken").isNotEmpty())
                .andExpect(jsonPath("$.shareableLink").isNotEmpty()));
    }

    private VerifiedGuest submitAndVerifyGuest(
            String shareToken,
            String counterpartyJson,
            CreatorRole expectedRole
    ) throws Exception {
        deliveredOtp.set(null);
        JsonNode challenge = responseJson(mockMvc.perform(
                        post("/orders/token/{shareToken}/counterparty-info", shareToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(counterpartyJson))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.counterpartyRole").value(expectedRole.name()))
                .andExpect(jsonPath("$.challengeId").isNotEmpty()));
        String otp = deliveredOtp.get();
        assertThat(otp).matches("\\d{6}");

        String verificationBody = mockMvc.perform(post("/orders/token/{shareToken}/verify-otp", shareToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"challengeId":"%s","code":"%s"}
                                """.formatted(challenge.path("challengeId").asText(), otp)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_SHIPPING_RATE"))
                .andExpect(jsonPath("$.counterpartyRole").value(expectedRole.name()))
                .andExpect(jsonPath("$.phoneVerified").value(true))
                .andExpect(jsonPath("$.guestAccessToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(verificationBody).doesNotContain("virtualAccount");
        JsonNode verification = objectMapper.readTree(verificationBody);
        assertThat(otpChallengeRepository.findByPublicId(challenge.path("challengeId").asText())
                .orElseThrow().getConsumedAt()).isNotNull();
        return new VerifiedGuest(verification.path("guestAccessToken").asText());
    }

    private JsonNode getPublicOrder(String shareToken) throws Exception {
        String body = mockMvc.perform(get("/orders/token/{shareToken}", shareToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(
                "virtualAccount",
                "paymentInstructions",
                "creatorEmail",
                "creatorPhoneNumber",
                "shareToken"
        );
        return objectMapper.readTree(body);
    }

    private JsonNode getGuestStatus(String shareToken, String guestToken) throws Exception {
        return responseJson(mockMvc.perform(get("/orders/token/{shareToken}/status", shareToken)
                        .header("X-Order-Access-Token", guestToken))
                .andExpect(status().isOk()));
    }

    private JsonNode requestAccessRecovery(String shareToken) throws Exception {
        deliveredOtp.set(null);
        return responseJson(mockMvc.perform(
                        post("/orders/token/{shareToken}/access-recovery-otp", shareToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challengeId").isNotEmpty()));
    }

    private String recoverAccess(String shareToken, String challengeId, String code) throws Exception {
        return responseJson(mockMvc.perform(post("/orders/token/{shareToken}/recover-access", shareToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"challengeId":"%s","code":"%s"}
                                """.formatted(challengeId, code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guestAccessToken").isNotEmpty()))
                .path("guestAccessToken").asText();
    }

    private void assertGuestAccessForbidden(String shareToken, String guestToken) throws Exception {
        mockMvc.perform(get("/orders/token/{shareToken}/status", shareToken)
                        .header("X-Order-Access-Token", guestToken))
                .andExpect(status().isForbidden());
    }

    private void assertRecoveryOtpDelivered(String phoneNumber, String email, String code) {
        assertThat(code).matches("\\d{6}");
        verify(smsProvider, atLeastOnce()).sendMessage(
                eq(phoneNumber),
                argThat(message -> message.contains(code) && message.contains("restore access to your order"))
        );
        verify(emailService, atLeastOnce()).sendEmail(
                eq(email),
                eq("Your ShopSecure verification code"),
                argThat(html -> html.contains(code) && html.contains("restore access to your order"))
        );
    }

    private JsonNode responseJson(ResultActions resultActions) throws Exception {
        return objectMapper.readTree(resultActions.andReturn().getResponse().getContentAsString());
    }

    private void assertPricing(JsonNode pricing, String deliveryFee, String totalAmount) {
        assertMoney(pricing, "/itemAmount", "125000.00");
        assertMoney(pricing, "/deliveryFee", deliveryFee);
        assertMoney(pricing, "/serviceFee", "500.00");
        assertMoney(pricing, "/totalAmount", totalAmount);
        assertThat(pricing.path("currency").asText()).isEqualTo("NGN");
    }

    private void assertPaymentAccount(JsonNode instructions, String amount) {
        assertThat(instructions.path("virtualAccount").path("accountNumber").asText())
                .isEqualTo(CREATOR_VIRTUAL_ACCOUNT);
        assertThat(instructions.path("virtualAccount").path("bankName").asText()).isEqualTo("Wema Bank");
        assertMoney(instructions, "/amount", amount);
        assertThat(instructions.path("currency").asText()).isEqualTo("NGN");
        assertThat(instructions.path("paymentReference").asText()).startsWith("ORD-");
    }

    private void assertMoney(JsonNode node, String pointer, String expected) {
        assertThat(node.at(pointer).decimalValue()).isEqualByComparingTo(expected);
    }

    private User createUser(String email, String phoneNumber, String firstname, String lastname) {
        User user = userRepository.save(User.builder()
                .firstname(firstname)
                .lastname(lastname)
                .email(email)
                .phoneNumber(phoneNumber)
                .password(passwordEncoder.encode("Secure1!"))
                .role(Role.USER)
                .createdAt(LocalDateTime.now())
                .emailVerifiedAt(LocalDateTime.now())
                .build());
        Instant now = Instant.now();
        walletRepository.save(Wallet.builder()
                .user(user)
                .balance(BigDecimal.ZERO)
                .currency("NGN")
                .createdAt(now)
                .updatedAt(now)
                .build());
        return user;
    }

    private void createVirtualAccount(User user, String accountNumber, String accountName) {
        virtualAccountRepository.save(VirtualAccount.builder()
                .user(user)
                .accountNumber(accountNumber)
                .bankName("Wema Bank")
                .accountName(accountName)
                .createdAt(Instant.now())
                .build());
    }

    private String orderJson(CreatorRole creatorRole, String parcel) {
        String parcelField = parcel == null ? "" : ",\n  \"parcel\":" + parcel;
        return """
                {
                  "amount":125000.00,
                  "itemDescription":"MacBook Air M3",
                  "creatorRole":"%s",
                  "creatorAddress":{
                    "line1":"20 Admiralty Way",
                    "line2":"Lekki Phase 1",
                    "city":"Lagos",
                    "state":"Lagos",
                    "country":"NG",
                    "postalCode":"101233"
                  }%s
                }
                """.formatted(creatorRole.name(), parcelField);
    }

    private String invalidAddressOrderJson() {
        return """
                {
                  "amount":125000.00,
                  "itemDescription":"MacBook Air M3",
                  "creatorRole":"BUYER",
                  "creatorAddress":{
                    "line1":"20 Admiralty Way",
                    "city":"Lagos",
                    "state":"Lagos",
                    "country":"Nigeria",
                    "postalCode":""
                  }
                }
                """;
    }

    private String vendorCounterpartyJson(boolean includeParcel) {
        String parcelField = includeParcel ? ",\n  \"parcel\":" + parcelJson("2.000") : "";
        return """
                {
                  "firstName":"Bola",
                  "lastName":"Vendor",
                  "email":"bola.vendor@example.com",
                  "phoneNumber":"%s",
                  "address":{
                    "line1":"12 Marina Road",
                    "line2":"Suite 4",
                    "city":"Lagos",
                    "state":"Lagos",
                    "country":"NG",
                    "postalCode":"100221"
                  },
                  "externalBankAccount":{
                    "accountNumber":"0123456789",
                    "bankCode":"%s"
                  }%s
                }
                """.formatted(GUEST_PHONE, TEST_BANK_CODE, parcelField);
    }

    private String buyerCounterpartyJson(boolean includeRestrictedSellerFields) {
        String restrictedFields = includeRestrictedSellerFields
                ? """
                ,
                  "externalBankAccount":{
                    "accountNumber":"0123456789",
                    "bankCode":"999"
                  },
                  "parcel":%s
                """.formatted(parcelJson("1.500"))
                : "";
        return """
                {
                  "firstName":"Bisi",
                  "lastName":"Buyer",
                  "email":"bisi.buyer@example.com",
                  "phoneNumber":"%s",
                  "address":{
                    "line1":"8 Broad Street",
                    "line2":"",
                    "city":"Lagos",
                    "state":"Lagos",
                    "country":"NG",
                    "postalCode":"100001"
                  }%s
                }
                """.formatted(GUEST_PHONE, restrictedFields);
    }

    private String parcelJson(String weightKg) {
        return """
                {
                  "weightKg":%s,
                  "lengthCm":40.00,
                  "widthCm":30.00,
                  "heightCm":15.00
                }
                """.formatted(weightKg).trim();
    }

    private void cleanDatabase() {
        processedWebhookRepository.deleteAllInBatch();
        settlementRepository.deleteAllInBatch();
        walletTransactionRepository.deleteAllInBatch();
        paymentReceiptRepository.deleteAllInBatch();
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

    private record VerifiedGuest(String accessToken) {
    }
}
