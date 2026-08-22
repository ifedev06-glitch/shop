package com.example.shopsecure.integration;

import com.example.shopsecure.ShopsecureApplication;
import com.example.shopsecure.entity.CreatorRole;
import com.example.shopsecure.entity.Order;
import com.example.shopsecure.entity.OrderCounterparty;
import com.example.shopsecure.entity.PaymentAttemptStatus;
import com.example.shopsecure.entity.PaymentStatus;
import com.example.shopsecure.entity.OrderStatus;
import com.example.shopsecure.entity.OrderTrackingNotificationStatus;
import com.example.shopsecure.entity.OrderTrackingNotificationType;
import com.example.shopsecure.entity.Role;
import com.example.shopsecure.entity.SettlementStatus;
import com.example.shopsecure.entity.SettlementTrigger;
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
import com.example.shopsecure.provider.payment.PayoutRequest;
import com.example.shopsecure.provider.payment.PayoutResponse;
import com.example.shopsecure.provider.payment.PayoutStatus;
import com.example.shopsecure.provider.payment.ResolvedBankAccount;
import com.example.shopsecure.provider.payment.VirtualAccountRequest;
import com.example.shopsecure.provider.payment.VirtualAccountResponse;
import com.example.shopsecure.provider.shipping.ShipmentStatus;
import com.example.shopsecure.provider.shipping.ShipmentStatusUpdatedEvent;
import com.example.shopsecure.repository.GuestOrderAccessTokenRepository;
import com.example.shopsecure.repository.OrderCounterpartyRepository;
import com.example.shopsecure.repository.OrderParcelRepository;
import com.example.shopsecure.repository.OrderPaymentAttemptRepository;
import com.example.shopsecure.repository.OrderRepository;
import com.example.shopsecure.repository.OrderSettlementRepository;
import com.example.shopsecure.repository.OrderShipmentRepository;
import com.example.shopsecure.repository.OrderStatusHistoryRepository;
import com.example.shopsecure.repository.OrderTrackingAccessTokenRepository;
import com.example.shopsecure.repository.OrderTrackingNotificationRepository;
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
import com.example.shopsecure.service.OrderShippingService;
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
import tools.jackson.databind.node.ObjectNode;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
    private OrderShippingService shippingService;
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
    @Autowired
    private OrderTrackingAccessTokenRepository trackingAccessRepository;
    @Autowired
    private OrderTrackingNotificationRepository trackingNotificationRepository;

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

        mockMvc.perform(get("/orders/token/{shareToken}", shareToken))
                .andExpect(status().isNotFound());
        verify(payoutProvider).createPayoutRecipient(argThat(request ->
                request.accountName().equals("Bola Vendor")
                        && request.accountNumber().equals("0123456789")
                        && request.bankCode().equals(TEST_BANK_CODE)
        ));
        JsonNode quotes = responseJson(mockMvc.perform(post("/orders/{orderId}/shipping/quotes", orderId)
                        .with(user(creator.getEmail()).roles("USER")))
                .andExpect(status().isOk()));
        assertThat(quotes.size()).isEqualTo(2);
        JsonNode standardQuote = quotes.get(0);
        assertMoney(standardQuote, "/deliveryFee", "5750.00");
        assertMoney(standardQuote, "/serviceFee", "500.00");
        assertMoney(standardQuote, "/totalAmount", "131250.00");
        assertThat(standardQuote.has("providerAmount")).isFalse();
        assertThat(standardQuote.has("deliveryMarkup")).isFalse();

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
        assertThat(selection.path("pricing").has("providerDeliveryFee")).isFalse();
        assertThat(selection.path("pricing").has("deliveryMarkup")).isFalse();
        assertThat(selection.path("fundingMode").asText()).isEqualTo("WALLET");
        assertThat(selection.hasNonNull("paymentInstructions")).isFalse();

        JsonNode creatorDetails = responseJson(mockMvc.perform(get("/orders/{orderId}", orderId)
                        .with(user(creator.getEmail()).roles("USER")))
                .andExpect(status().isOk()));
        assertThat(creatorDetails.path("fundingMode").asText()).isEqualTo("WALLET");
        assertThat(creatorDetails.hasNonNull("paymentInstructions")).isFalse();
        assertThat(creatorDetails.hasNonNull("buyerPaymentLink")).isFalse();
        mockMvc.perform(post("/orders/token/{shareToken}/payments/checkout", shareToken)
                        .header("X-Order-Access-Token", "not-issued"))
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

        JsonNode trackedOrder = responseJson(mockMvc.perform(get("/orders/{orderId}", orderId)
                        .with(user(creator.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipping.shopSecureTrackingUrl").isNotEmpty())
                .andExpect(jsonPath("$.shipping.trackingAvailableAt").isNotEmpty()));
        String trackingUrl = trackedOrder.path("shipping").path("shopSecureTrackingUrl").asText();
        assertThat(trackingUrl).startsWith("http://localhost:3000/orders/tracking/");
        String trackingToken = trackingUrl.substring(trackingUrl.lastIndexOf('/') + 1);
        mockMvc.perform(get("/orders/tracking/{trackingToken}", trackingToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow, noarchive"))
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.itemDescription").value("MacBook Air M3"))
                .andExpect(jsonPath("$.itemAmount").value(125000.00))
                .andExpect(jsonPath("$.orderStatus").value("READY_FOR_DELIVERY"))
                .andExpect(jsonPath("$.trackingStatus").value("AWAITING_PICKUP"))
                .andExpect(jsonPath("$.paymentStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.buyer.firstName").value("Ada"))
                .andExpect(jsonPath("$.buyer.email").value(CREATOR_EMAIL))
                .andExpect(jsonPath("$.buyer.phoneNumber").value("+" + creator.getPhoneNumber()))
                .andExpect(jsonPath("$.buyer.address.line1").value("20 Admiralty Way"))
                .andExpect(jsonPath("$.vendor.firstName").value("Bola"))
                .andExpect(jsonPath("$.vendor.email").value("bola.vendor@example.com"))
                .andExpect(jsonPath("$.vendor.phoneNumber").value("+" + GUEST_PHONE))
                .andExpect(jsonPath("$.vendor.address.line1").value("12 Marina Road"))
                .andExpect(jsonPath("$.vendorPayoutAccount.bankAccountLast4").value("6789"))
                .andExpect(jsonPath("$.vendorPayoutAccount.bankName").value(TEST_BANK_NAME))
                .andExpect(jsonPath("$.vendorPayoutAccount.bankAccountName").value("Bola Vendor"))
                .andExpect(jsonPath("$.pricing.itemAmount").value(125000.00))
                .andExpect(jsonPath("$.pricing.totalAmount").value(131250.00))
                .andExpect(jsonPath("$.parcel.weightKg").value(2.000))
                .andExpect(jsonPath("$.shipment.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.shipment.selectedQuote.carrierName").value("Mock Logistics"))
                .andExpect(jsonPath("$.shipment.trackingReference").isNotEmpty())
                .andExpect(jsonPath("$.escrow.releasePolicy").value("CARRIER_PICKUP"))
                .andExpect(jsonPath("$.escrow.status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.escrow.amountPending").value(125000.00))
                .andExpect(jsonPath("$.escrow.amountReleased").value(0.00))
                .andExpect(jsonPath("$.trackingAvailableAt").isNotEmpty())
                .andExpect(jsonPath("$.trackingExpiresAt").isNotEmpty());
        var bookedShipment = shipmentRepository.findByOrder_Id(orderId).orElseThrow();
        shippingService.applyWebhookEvent(
                new ShipmentStatusUpdatedEvent(
                        bookedShipment.getProviderShipmentId(),
                        ShipmentStatus.IN_TRANSIT,
                        bookedShipment.getProviderUpdatedAt().plusSeconds(1),
                        true
                ),
                "tracking-link-in-transit"
        );
        mockMvc.perform(get("/orders/tracking/{trackingToken}", trackingToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackingStatus").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.orderStatus").value("PAYOUT_PENDING"))
                .andExpect(jsonPath("$.carrierPickupConfirmedAt").isNotEmpty())
                .andExpect(jsonPath("$.escrow.status").value("PENDING"))
                .andExpect(jsonPath("$.escrow.settlementType").value("EXTERNAL_PAYOUT"))
                .andExpect(jsonPath("$.escrow.trigger").value("CARRIER_PICKUP"))
                .andExpect(jsonPath("$.escrow.amountPending").value(125000.00))
                .andExpect(jsonPath("$.escrow.amountReleased").value(0.00))
                .andExpect(jsonPath("$.escrow.payoutReference").isNotEmpty());
        JsonNode inTransitOrder = responseJson(mockMvc.perform(get("/orders/{orderId}", orderId)
                        .with(user(creator.getEmail()).roles("USER")))
                .andExpect(status().isOk()));
        assertThat(inTransitOrder.path("shipping").path("shopSecureTrackingUrl").asText())
                .isEqualTo(trackingUrl);
        mockMvc.perform(get("/orders/tracking/{trackingToken}", "not-a-valid-token"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
        assertThat(trackingAccessRepository.count()).isOne();
        var persistedTrackingAccess = trackingAccessRepository.findByOrder_Id(orderId).orElseThrow();
        assertThat(persistedTrackingAccess.getTokenHash()).doesNotContain(trackingToken);
        assertThat(persistedTrackingAccess.getTokenCiphertext()).doesNotContain(trackingToken);
        assertThat(trackingNotificationRepository.findAll())
                .hasSize(4)
                .allSatisfy(notification ->
                        assertThat(notification.getStatus()).isEqualTo(OrderTrackingNotificationStatus.SENT));
        assertThat(trackingNotificationRepository.findAll())
                .extracting(notification -> notification.getNotificationType())
                .containsOnly(
                        OrderTrackingNotificationType.TRACKING_READY,
                        OrderTrackingNotificationType.CARRIER_PICKUP_CONFIRMED
                );

        mockMvc.perform(get("/orders/token/{shareToken}", shareToken))
                .andExpect(status().isNotFound());

        Order selectedOrder = orderRepository.findById(orderId).orElseThrow();
        assertThat(selectedOrder.getStatus()).isEqualTo(OrderStatus.PAYOUT_PENDING);
        assertThat(selectedOrder.getSettlementStatus()).isEqualTo(SettlementStatus.PENDING);
        assertThat(selectedOrder.getCarrierPickupConfirmedAt()).isNotNull();
        assertThat(selectedOrder.getDeliveryConfirmedAt()).isNull();
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
                eq("+" + GUEST_PHONE),
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
        verify(emailService, times(1)).sendEmail(
                eq(CREATOR_EMAIL),
                eq("Track your ShopSecure order"),
                argThat(html -> html.contains(trackingUrl) && html.contains("Buyer")),
                argThat(key -> key.startsWith("order-tracking-ready/"))
        );
        verify(emailService, times(1)).sendEmail(
                eq("bola.vendor@example.com"),
                eq("Track your ShopSecure order"),
                argThat(html -> html.contains(trackingUrl) && html.contains("Vendor")),
                argThat(key -> key.startsWith("order-tracking-ready/"))
        );
        verify(emailService, times(1)).sendEmail(
                eq(CREATOR_EMAIL),
                eq("Carrier pickup confirmed"),
                argThat(html -> html.contains("carrier confirmed that the package")
                        && html.contains("queued the item-fund payout")
                        && html.contains(trackingUrl)),
                argThat(key -> key.startsWith("order-carrier-pickup/"))
        );
        verify(emailService, times(1)).sendEmail(
                eq("bola.vendor@example.com"),
                eq("Carrier pickup confirmed"),
                argThat(html -> html.contains("carrier confirmed that the package")
                        && html.contains("queued the item-fund payout")
                        && html.contains(trackingUrl)),
                argThat(key -> key.startsWith("order-carrier-pickup/"))
        );
        verify(smsProvider, times(1)).sendMessage(
                eq("+" + creator.getPhoneNumber()),
                argThat(message -> message.contains("Your order has been created")
                        && message.contains("vendor has been notified"))
        );
        var settlement = settlementRepository.findByOrder_Id(orderId).orElseThrow();
        assertThat(settlement.getTrigger()).isEqualTo(SettlementTrigger.CARRIER_PICKUP);
        assertThat(settlement.getAmount()).isEqualByComparingTo("125000.00");
        verify(payoutProvider, times(1)).initiatePayout(argThat((PayoutRequest request) ->
                request.recipientId().equals("RCP_TEST_VENDOR")
                        && request.amount().compareTo(new BigDecimal("125000.00")) == 0
        ));

        persistedTrackingAccess.setExpiresAt(Instant.now().minusSeconds(1));
        persistedTrackingAccess = trackingAccessRepository.saveAndFlush(persistedTrackingAccess);
        mockMvc.perform(get("/orders/tracking/{trackingToken}", trackingToken))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"));

        persistedTrackingAccess.setExpiresAt(Instant.now().plusSeconds(60));
        persistedTrackingAccess.setRevokedAt(Instant.now());
        trackingAccessRepository.saveAndFlush(persistedTrackingAccess);
        mockMvc.perform(get("/orders/tracking/{trackingToken}", trackingToken))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void vendorCreatedOrderReplaysActiveCheckoutAndReplacesVerifiedFailure() throws Exception {
        JsonNode created = createOrder(CreatorRole.VENDOR, parcelJson("3.000"));
        long orderId = created.path("id").asLong();
        String shareToken = created.path("shareToken").asText();
        assertThat(created.path("fundingMode").asText()).isEqualTo("PAYSTACK_CHECKOUT");

        mockMvc.perform(get("/orders/token/{shareToken}", shareToken))
                .andExpect(status().isNotFound());
        String creatorDetailsBeforeVerification = mockMvc.perform(get("/orders/{orderId}", orderId)
                        .with(user(creator.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(creatorDetailsBeforeVerification).doesNotContain("virtualAccount");

        JsonNode quotes = responseJson(mockMvc.perform(post("/orders/{orderId}/shipping/quotes", orderId)
                        .with(user(creator.getEmail()).roles("USER")))
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
                        put("/orders/{orderId}/shipping/selection", orderId)
                                .with(user(creator.getEmail()).roles("USER"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"quoteId\":" + quoteId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_PAYMENT")));
        assertPricing(selection.path("pricing"), "6000.00", "131500.00");
        assertThat(selection.path("fundingMode").asText()).isEqualTo("PAYSTACK_CHECKOUT");
        assertThat(selection.hasNonNull("paymentInstructions")).isFalse();
        assertThat(selection.path("buyerPaymentLink").asText()).endsWith("/orders/token/" + shareToken);

        getPublicOrder(shareToken);
        VerifiedGuest buyer = submitAndVerifyGuest(
                shareToken,
                buyerCounterpartyJson(false),
                CreatorRole.BUYER
        );

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
        verify(payoutProvider).createPayoutRecipient(any());
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
                        && request.phone().equals("+2348066666666")
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
                .andExpect(jsonPath("$.phoneNumber").value("+2348055555555"))
                .andExpect(jsonPath("$.virtualAccount.accountNumber").value("9999999999"))
                .andExpect(jsonPath("$.virtualAccount.bankName").value("Wema Bank"));

        User updated = userRepository.findById(incompleteUser.getId()).orElseThrow();
        VirtualAccount account = virtualAccountRepository.findByUser_Id(incompleteUser.getId()).orElseThrow();
        assertThat(updated.getPhoneNumber()).isEqualTo("+2348055555555");
        assertThat(account.getAccountNumber()).isEqualTo("9999999999");
        verify(paymentProvider).createVirtualAccount(argThat(request ->
                request.customerName().equals("Updated Member")
                        && request.email().equals("existing-member@example.com")
                        && request.phone().equals("+2348055555555")
                        && request.customerReference().equals("user-" + incompleteUser.getId())
        ));
    }

    @Test
    void buyerPaymentOtpResendInvalidatesPriorChallengeAndOnlyLatestVerifies() throws Exception {
        JsonNode created = createOrder(CreatorRole.VENDOR, parcelJson("3.000"));
        long orderId = created.path("id").asLong();
        String shareToken = created.path("shareToken").asText();
        JsonNode quotes = responseJson(mockMvc.perform(post("/orders/{orderId}/shipping/quotes", orderId)
                        .with(user(creator.getEmail()).roles("USER")))
                .andExpect(status().isOk()));
        mockMvc.perform(put("/orders/{orderId}/shipping/selection", orderId)
                        .with(user(creator.getEmail()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quoteId\":" + quotes.get(0).path("quoteId").asLong() + "}"))
                .andExpect(status().isOk());

        JsonNode firstChallenge = responseJson(mockMvc.perform(
                        post("/orders/token/{shareToken}/payment-access-otp", shareToken))
                .andExpect(status().isAccepted()));
        String firstCode = deliveredOtp.get();
        var priorChallenge = otpChallengeRepository
                .findByPublicId(firstChallenge.path("challengeId").asText())
                .orElseThrow();
        priorChallenge.setCreatedAt(LocalDateTime.now().minusMinutes(2));
        otpChallengeRepository.saveAndFlush(priorChallenge);

        deliveredOtp.set(null);
        JsonNode latestChallenge = responseJson(mockMvc.perform(
                        post("/orders/token/{shareToken}/resend-otp", shareToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"challengeId\":\""
                                        + firstChallenge.path("challengeId").asText() + "\"}"))
                .andExpect(status().isOk()));
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
                .andExpect(jsonPath("$.status").value("AWAITING_PAYMENT"))
                .andExpect(jsonPath("$.guestAccessToken").isNotEmpty());

        var counterparty = counterpartyRepository.findByOrder_Id(orderId).orElseThrow();
        assertThat(counterparty.getPhoneNumber()).isEqualTo("+" + GUEST_PHONE);
        assertThat(counterparty.getEmail()).isEqualTo("bisi.buyer@example.com");
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
        selectFirstCreatorQuote(orderId);
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
        assertRecoveryOtpDelivered("+2348012345678", "bisi.buyer@example.com", revokedRecoveryCode);
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
        assertRecoveryOtpDelivered("+2348012345678", "bisi.buyer@example.com", expiredRecoveryCode);
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
        JsonNode selection = selectFirstCreatorQuote(orderId);
        VerifiedGuest buyer = submitAndVerifyGuest(
                shareToken,
                buyerCounterpartyJson(false),
                CreatorRole.BUYER
        );
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
        String trackingUrl = fundedStatus.path("shipping").path("shopSecureTrackingUrl").asText();
        assertThat(trackingUrl).startsWith("http://localhost:3000/orders/tracking/");
        JsonNode creatorFundedOrder = responseJson(mockMvc.perform(get("/orders/{orderId}", orderId)
                        .with(user(creator.getEmail()).roles("USER")))
                .andExpect(status().isOk()));
        assertThat(creatorFundedOrder.path("shipping").path("shopSecureTrackingUrl").asText())
                .isEqualTo(trackingUrl);
        String trackingToken = trackingUrl.substring(trackingUrl.lastIndexOf('/') + 1);
        mockMvc.perform(get("/orders/tracking/{trackingToken}", trackingToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackingStatus").value("AWAITING_PICKUP"));
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
                eq("+" + creator.getPhoneNumber()),
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
                eq("+" + GUEST_PHONE),
                argThat(message -> message.contains("Your order has been created")
                        && message.contains("vendor has been notified"))
        );
    }

    @Test
    void publicVerificationIssuesTokenWhileGuestAndCreatorDetailsRemainProtected() throws Exception {
        JsonNode created = createOrder(CreatorRole.VENDOR, parcelJson("2.000"));
        long orderId = created.path("id").asLong();
        String shareToken = created.path("shareToken").asText();
        selectFirstCreatorQuote(orderId);

        VerifiedGuest buyer = submitAndVerifyGuest(
                shareToken,
                buyerCounterpartyJson(false),
                CreatorRole.BUYER
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
                        .header("X-Order-Access-Token", buyer.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_PAYMENT"));
    }

    @Test
    void completeOrderParcelAndIsoCountryAndPostalCodeAreValidated() throws Exception {
        mockMvc.perform(post("/orders")
                        .with(user(creator.getEmail()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(CreatorRole.VENDOR, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.parcel").value("Parcel is required"));

        mockMvc.perform(post("/orders")
                        .with(user(creator.getEmail()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(CreatorRole.BUYER, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.parcel").value("Parcel is required"));

        JsonNode addressErrors = responseJson(mockMvc.perform(post("/orders")
                        .with(user(creator.getEmail()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidAddressOrderJson()))
                .andExpect(status().isBadRequest()));
        assertThat(addressErrors.path("details").path("creatorAddress.country").asText())
                .isEqualTo("Only Nigerian shipping addresses are supported");
        assertThat(addressErrors.path("details").path("creatorAddress.postalCode").asText())
                .isEqualTo("Postal code is required");

    }

    @Test
    void creatorOrdersArePaginatedNewestFirst() throws Exception {
        JsonNode firstOrder = createOrder(CreatorRole.BUYER, null);
        JsonNode secondOrder = createOrder(CreatorRole.BUYER, null);
        long firstId = firstOrder.path("id").asLong();
        long secondId = secondOrder.path("id").asLong();

        mockMvc.perform(get("/orders")
                        .with(user(creator.getEmail()).roles("USER"))
                        .param("page", "0")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(secondId))
                .andExpect(jsonPath("$.content[0].buyerPaymentLink").doesNotExist())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(false));

        mockMvc.perform(get("/orders")
                        .with(user(creator.getEmail()).roles("USER"))
                        .param("page", "1")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(firstId))
                .andExpect(jsonPath("$.content[0].buyerPaymentLink").doesNotExist())
                .andExpect(jsonPath("$.first").value(false))
                .andExpect(jsonPath("$.last").value(true));

        mockMvc.perform(get("/orders/{orderId}", secondId)
                .with(user(creator.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buyerPaymentLink").doesNotExist());
    }

    @Test
    void sellerPendingAmountAggregatesActiveSellerProceedsAcrossBothParticipantPositions() throws Exception {
        Order activeUnfunded = orderRepository.findById(
                createOrder(CreatorRole.VENDOR, parcelJson("1.000")).path("id").asLong()
        ).orElseThrow();
        Order activeFunded = orderRepository.findById(
                createOrder(CreatorRole.VENDOR, parcelJson("1.000")).path("id").asLong()
        ).orElseThrow();
        activeFunded.setStatus(OrderStatus.READY_FOR_DELIVERY);
        activeFunded.setPaymentStatus(PaymentStatus.CONFIRMED);
        activeFunded.setFundedAt(Instant.now());
        activeFunded.setUpdatedAt(Instant.now());
        orderRepository.save(activeFunded);

        Order cancelled = orderRepository.findById(
                createOrder(CreatorRole.VENDOR, parcelJson("1.000")).path("id").asLong()
        ).orElseThrow();
        cancelled.setStatus(OrderStatus.CANCELLED);
        cancelled.setUpdatedAt(Instant.now());
        orderRepository.save(cancelled);

        Order settled = orderRepository.findById(
                createOrder(CreatorRole.VENDOR, parcelJson("1.000")).path("id").asLong()
        ).orElseThrow();
        settled.setStatus(OrderStatus.COMPLETED);
        settled.setPaymentStatus(PaymentStatus.CONFIRMED);
        settled.setSettlementStatus(SettlementStatus.COMPLETED);
        settled.setFundedAt(Instant.now());
        settled.setCompletedAt(Instant.now());
        settled.setUpdatedAt(Instant.now());
        orderRepository.save(settled);

        createVirtualAccount(otherUser, "2222222222", "OTHER BUYER");
        long buyerCreatedOrderId = responseJson(mockMvc.perform(post("/orders")
                        .with(user(otherUser.getEmail()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(CreatorRole.BUYER, parcelJson("1.000"))))
                .andExpect(status().isCreated()))
                .path("id").asLong();
        Order buyerCreatedOrder = orderRepository.findById(buyerCreatedOrderId).orElseThrow();
        buyerCreatedOrder.setStatus(OrderStatus.AWAITING_PAYMENT);
        buyerCreatedOrder.setUpdatedAt(Instant.now());
        orderRepository.save(buyerCreatedOrder);
        OrderCounterparty sellerCounterparty = counterpartyRepository
                .findByOrder_Id(buyerCreatedOrderId)
                .orElseThrow();
        sellerCounterparty.setUser(creator);
        sellerCounterparty.setFirstName(creator.getFirstname());
        sellerCounterparty.setLastName(creator.getLastname());
        sellerCounterparty.setPhoneNumber(creator.getPhoneNumber());
        sellerCounterparty.setEmail(creator.getEmail());
        sellerCounterparty.setPhoneVerified(true);
        sellerCounterparty.setUpdatedAt(Instant.now());
        counterpartyRepository.save(sellerCounterparty);

        JsonNode summary = responseJson(mockMvc.perform(get("/orders/seller/pending-amount")
                        .with(user(creator.getEmail()).roles("USER")))
                .andExpect(status().isOk()));
        assertThat(summary.path("pendingAmount").decimalValue())
                .isEqualByComparingTo("375000.00");
        assertThat(summary.path("fundedPendingAmount").decimalValue())
                .isEqualByComparingTo("125000.00");
        assertThat(summary.path("awaitingFundingAmount").decimalValue())
                .isEqualByComparingTo("250000.00");
        assertThat(summary.path("activeOrderCount").asLong()).isEqualTo(3);
        assertThat(summary.path("fundedOrderCount").asLong()).isEqualTo(1);
        assertThat(summary.path("currency").asText()).isEqualTo("NGN");
        assertThat(summary.path("asOf").asText()).isNotBlank();

        mockMvc.perform(get("/orders/seller/pending-amount")
                        .with(user(otherUser.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingAmount").value(0.00))
                .andExpect(jsonPath("$.activeOrderCount").value(0));
        mockMvc.perform(get("/orders/seller/pending-amount"))
                .andExpect(status().isForbidden());

        assertThat(activeUnfunded.getPaymentStatus()).isEqualTo(PaymentStatus.NOT_STARTED);
    }

    private JsonNode createOrder(CreatorRole creatorRole, String parcel) throws Exception {
        String completeParcel = parcel == null ? parcelJson("2.000") : parcel;
        JsonNode response = responseJson(mockMvc.perform(post("/orders")
                        .with(user(creator.getEmail()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(creatorRole, completeParcel)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AWAITING_SHIPPING_RATE"))
                .andExpect(jsonPath("$.creatorRole").value(creatorRole.name()))
                .andExpect(jsonPath("$.counterpartyRole").value(creatorRole.opposite().name()))
                .andExpect(jsonPath("$.shareToken").doesNotExist())
                .andExpect(jsonPath("$.shareableLink").doesNotExist()));
        Order persisted = orderRepository.findById(response.path("id").asLong()).orElseThrow();
        ((ObjectNode) response).put("shareToken", persisted.getShareToken());
        return response;
    }

    private VerifiedGuest submitAndVerifyGuest(
            String shareToken,
            String ignoredCounterpartyJson,
            CreatorRole expectedRole
    ) throws Exception {
        assertThat(expectedRole).isEqualTo(CreatorRole.BUYER);
        deliveredOtp.set(null);
        JsonNode challenge = responseJson(mockMvc.perform(
                        post("/orders/token/{shareToken}/payment-access-otp", shareToken))
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
                .andExpect(jsonPath("$.status").value("AWAITING_PAYMENT"))
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

    private JsonNode selectFirstCreatorQuote(long orderId) throws Exception {
        JsonNode quotes = responseJson(mockMvc.perform(post("/orders/{orderId}/shipping/quotes", orderId)
                        .with(user(creator.getEmail()).roles("USER")))
                .andExpect(status().isOk()));
        return responseJson(mockMvc.perform(put("/orders/{orderId}/shipping/selection", orderId)
                        .with(user(creator.getEmail()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quoteId\":" + quotes.get(0).path("quoteId").asLong() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_PAYMENT")));
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
        String counterparty = creatorRole == CreatorRole.BUYER
                ? vendorPartyJson()
                : buyerPartyJson();
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
                  },
                  "counterparty":%s,
                  "vendorBankAccount":{
                    "accountNumber":"0123456789",
                    "bankCode":"%s"
                  }%s
                }
                """.formatted(creatorRole.name(), counterparty, TEST_BANK_CODE, parcelField);
    }

    private String invalidAddressOrderJson() {
        return orderJson(CreatorRole.BUYER, parcelJson("2.000"))
                .replace("\"country\":\"NG\",\n    \"postalCode\":\"101233\"",
                        "\"country\":\"Nigeria\",\n    \"postalCode\":\"\"");
    }

    private String vendorPartyJson() {
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
                  }
                }
                """.formatted(GUEST_PHONE).trim();
    }

    private String buyerPartyJson() {
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
                  }
                }
                """.formatted(GUEST_PHONE).trim();
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
