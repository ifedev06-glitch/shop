package com.example.shopsecure.integration;

import com.example.shopsecure.ShopsecureApplication;
import com.example.shopsecure.dto.response.DeliveryConfirmationResponse;
import com.example.shopsecure.dto.response.OtpChallengeResponse;
import com.example.shopsecure.entity.CreatorRole;
import com.example.shopsecure.entity.Order;
import com.example.shopsecure.entity.OrderCounterparty;
import com.example.shopsecure.entity.OrderPaymentAttempt;
import com.example.shopsecure.entity.OrderShipment;
import com.example.shopsecure.entity.OrderSettlement;
import com.example.shopsecure.entity.OrderFundingMode;
import com.example.shopsecure.entity.OrderStatus;
import com.example.shopsecure.entity.PaymentReceipt;
import com.example.shopsecure.entity.PaymentReceiptClassification;
import com.example.shopsecure.entity.PaymentAttemptStatus;
import com.example.shopsecure.entity.PaymentStatus;
import com.example.shopsecure.entity.Role;
import com.example.shopsecure.entity.SettlementStatus;
import com.example.shopsecure.entity.SettlementType;
import com.example.shopsecure.entity.ShippingQuote;
import com.example.shopsecure.entity.ShippingQuoteStatus;
import com.example.shopsecure.entity.User;
import com.example.shopsecure.entity.VirtualAccount;
import com.example.shopsecure.entity.Wallet;
import com.example.shopsecure.entity.WalletTransaction;
import com.example.shopsecure.entity.WalletTransactionType;
import com.example.shopsecure.entity.WalletPaymentOutcome;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.provider.common.WebhookPayload;
import com.example.shopsecure.provider.notification.SmsProvider;
import com.example.shopsecure.provider.payment.PaymentProvider;
import com.example.shopsecure.provider.payment.PaymentStatusUpdatedEvent;
import com.example.shopsecure.provider.payment.PaymentTransactionStatus;
import com.example.shopsecure.provider.payment.PayoutProvider;
import com.example.shopsecure.provider.payment.PayoutRequest;
import com.example.shopsecure.provider.payment.PayoutResponse;
import com.example.shopsecure.provider.payment.PayoutStatus;
import com.example.shopsecure.provider.payment.PayoutStatusUpdatedEvent;
import com.example.shopsecure.provider.shipping.ShipmentResponse;
import com.example.shopsecure.provider.shipping.ShipmentStatus;
import com.example.shopsecure.provider.shipping.ShipmentStatusUpdatedEvent;
import com.example.shopsecure.provider.shipping.ShipmentTrackingStatus;
import com.example.shopsecure.provider.shipping.ShippingProvider;
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
import com.example.shopsecure.service.GuestOrderAccessService;
import com.example.shopsecure.service.GuestOrderStatusService;
import com.example.shopsecure.service.OrderPaymentService;
import com.example.shopsecure.service.OrderFundedListener;
import com.example.shopsecure.service.OrderSettlementService;
import com.example.shopsecure.service.OrderShippingService;
import com.example.shopsecure.service.OrderWalletFundingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = {
        ShopsecureApplication.class,
        TestConfig.class,
        ReconciliationSettlementTestConfig.class
})
@ActiveProfiles("test")
class PaymentReconciliationAndSettlementIntegrationTest {

    private static final BigDecimal ITEM_AMOUNT = new BigDecimal("125000.00");
    private static final BigDecimal PROVIDER_DELIVERY_FEE = new BigDecimal("5000.00");
    private static final BigDecimal DELIVERY_MARKUP = new BigDecimal("1000.00");
    private static final BigDecimal DELIVERY_FEE = new BigDecimal("6000.00");
    private static final BigDecimal SERVICE_FEE = new BigDecimal("500.00");
    private static final BigDecimal TOTAL_AMOUNT = new BigDecimal("131500.00");
    private static final String CURRENCY = "NGN";
    private static final String BUYER_CREATOR_EMAIL = "buyer.creator@example.com";
    private static final Instant FUNDED_AT = Instant.parse("2026-08-07T10:15:30Z");
    private static final Pattern OTP_PATTERN = Pattern.compile("\\b(\\d{6})\\b");

    @Autowired
    private OrderPaymentService paymentService;
    @Autowired
    private OrderSettlementService settlementService;
    @Autowired
    private OrderShippingService shippingService;
    @Autowired
    private OrderFundedListener fundedListener;
    @Autowired
    private OrderWalletFundingService walletFundingService;
    @Autowired
    private GuestOrderAccessService guestAccessService;
    @Autowired
    private GuestOrderStatusService guestStatusService;
    @Autowired
    private PaymentProvider paymentProvider;
    @Autowired
    private PayoutProvider payoutProvider;
    @Autowired
    private ShippingProvider shippingProvider;
    @Autowired
    private SmsProvider smsProvider;
    @Autowired
    private EmailService emailService;
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

    @BeforeEach
    void setUp() {
        cleanDatabase();
        reset(paymentProvider, payoutProvider, shippingProvider, smsProvider, emailService);
        when(shippingProvider.trackShipment(anyString())).thenReturn(new ShipmentTrackingStatus(
                ShipmentStatus.DRAFT,
                Instant.now(),
                List.of()
        ));
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void exactDvaPaymentConfirmsAndBooksTheSelectedShipmentAfterCommit() {
        User creator = createCreator("exact-payment@example.com", "1111111111");
        PaymentFixture fixture = createAwaitingPaymentFixture(creator);
        when(shippingProvider.arrangePickup(
                fixture.shipment().getProviderShipmentId(),
                fixture.quote().getProviderRateId()
        )).thenReturn(confirmedShipment(fixture.shipment()));

        paymentService.handleEvent(paymentEvent(
                "payment-event-exact",
                "pay-reference-exact",
                "1111111111",
                TOTAL_AMOUNT,
                FUNDED_AT
        ));

        Order funded = orderRepository.findById(fixture.order().getId()).orElseThrow();
        PaymentReceipt receipt = receiptRepository.findTopByOrder_IdOrderByCreatedAtDesc(funded.getId())
                .orElseThrow();
        OrderShipment shipment = shipmentRepository.findByOrder_Id(funded.getId()).orElseThrow();
        ShippingQuote quote = quoteRepository.findById(fixture.quote().getId()).orElseThrow();

        assertThat(receiptRepository.count()).isOne();
        assertThat(receipt.getClassification()).isEqualTo(PaymentReceiptClassification.MATCHED);
        assertThat(receipt.getProviderEventKey()).isEqualTo("payment-event-exact");
        assertThat(receipt.getReceiverAccountNumber()).isEqualTo("1111111111");
        assertThat(receipt.getAmount()).isEqualByComparingTo(TOTAL_AMOUNT);
        assertThat(funded.getPaymentStatus()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(funded.getFundedAt()).isEqualTo(FUNDED_AT);
        assertThat(funded.getStatus()).isEqualTo(OrderStatus.READY_FOR_DELIVERY);
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CONFIRMED);
        assertThat(quote.getStatus()).isEqualTo(ShippingQuoteStatus.USED);
        verify(shippingProvider).arrangePickup(
                fixture.shipment().getProviderShipmentId(),
                fixture.quote().getProviderRateId()
        );
    }

    @Test
    void duplicatePaymentEventDoesNotCreateAnotherReceiptOrBookShippingAgain() {
        User creator = createCreator("duplicate-payment@example.com", "2222222222");
        PaymentFixture fixture = createAwaitingPaymentFixture(creator);
        when(shippingProvider.arrangePickup(anyString(), anyString()))
                .thenReturn(confirmedShipment(fixture.shipment()));
        PaymentStatusUpdatedEvent event = paymentEvent(
                "payment-event-duplicate",
                "pay-reference-duplicate",
                "2222222222",
                TOTAL_AMOUNT,
                FUNDED_AT
        );

        paymentService.handleEvent(event);
        paymentService.handleEvent(event);

        Order order = orderRepository.findById(fixture.order().getId()).orElseThrow();
        assertThat(receiptRepository.count()).isOne();
        assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.READY_FOR_DELIVERY);
        verify(shippingProvider, times(1)).arrangePickup(
                fixture.shipment().getProviderShipmentId(),
                fixture.quote().getProviderRateId()
        );
    }

    @Test
    @WithMockUser(username = BUYER_CREATOR_EMAIL, roles = "USER")
    void existingWalletBalanceFundsBuyerOrderOnceWithStrictIdempotency() {
        User creator = createCreator(BUYER_CREATOR_EMAIL, "2020202020");
        PaymentFixture fixture = createAwaitingPaymentFixture(creator, OrderFundingMode.WALLET);
        Wallet wallet = walletRepository.findByUser_Id(creator.getId()).orElseThrow();
        wallet.setBalance(TOTAL_AMOUNT.add(new BigDecimal("1000.00")));
        walletRepository.save(wallet);
        when(shippingProvider.arrangePickup(anyString(), anyString()))
                .thenReturn(confirmedShipment(fixture.shipment()));

        var first = walletFundingService.pay(fixture.order().getId(), "wallet-payment-key");
        var replay = walletFundingService.pay(fixture.order().getId(), "wallet-payment-key");

        Order funded = orderRepository.findById(fixture.order().getId()).orElseThrow();
        Wallet debited = walletRepository.findByUser_Id(creator.getId()).orElseThrow();
        WalletTransaction transaction = walletTransactionRepository
                .findByOrder_IdAndTransactionType(
                        funded.getId(),
                        WalletTransactionType.ORDER_PAYMENT_DEBIT
                )
                .orElseThrow();
        assertThat(first.outcome()).isEqualTo(WalletPaymentOutcome.FUNDED);
        assertThat(replay.outcome()).isEqualTo(WalletPaymentOutcome.FUNDED);
        assertThat(first.amountRequired()).isEqualByComparingTo(TOTAL_AMOUNT);
        assertThat(first.walletBalance()).isEqualByComparingTo("1000.00");
        assertThat(funded.getPaymentStatus()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(funded.getStatus()).isEqualTo(OrderStatus.READY_FOR_DELIVERY);
        assertThat(debited.getBalance()).isEqualByComparingTo("1000.00");
        assertThat(transaction.getAmount()).isEqualByComparingTo(TOTAL_AMOUNT);
        assertThat(transaction.getBalanceBefore()).isEqualByComparingTo("132500.00");
        assertThat(transaction.getBalanceAfter()).isEqualByComparingTo("1000.00");
        assertThat(walletTransactionRepository.count()).isOne();
        assertThat(receiptRepository.count()).isZero();
        verify(shippingProvider, times(1)).arrangePickup(anyString(), anyString());

        assertThatThrownBy(() -> walletFundingService.pay(funded.getId(), "different-key"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("A different idempotency key was already used for this wallet payment");
        assertThat(walletRepository.findByUser_Id(creator.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("1000.00");
    }

    @Test
    void duplicateTopUpCreditsWalletOnceAndLeavesWalletOrderAwaitingManualRetry() {
        User creator = createCreator("wallet-top-up@example.com", "2120202020");
        PaymentFixture fixture = createAwaitingPaymentFixture(creator, OrderFundingMode.WALLET);
        PaymentStatusUpdatedEvent event = paymentEvent(
                "wallet-top-up-event",
                "wallet-top-up-reference",
                "2120202020",
                new BigDecimal("50000.00"),
                FUNDED_AT
        );

        paymentService.handleEvent(event);
        paymentService.handleEvent(event);

        Order order = orderRepository.findById(fixture.order().getId()).orElseThrow();
        Wallet wallet = walletRepository.findByUser_Id(creator.getId()).orElseThrow();
        PaymentReceipt receipt = receiptByEventKey("wallet-top-up-event");
        assertThat(receipt.getClassification()).isEqualTo(PaymentReceiptClassification.WALLET_CREDITED);
        assertThat(receipt.getOrder()).isNull();
        assertThat(wallet.getBalance()).isEqualByComparingTo("50000.00");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(order.getFundedAt()).isNull();
        assertThat(receiptRepository.count()).isOne();
        assertThat(walletTransactionRepository.count()).isOne();
        verify(shippingProvider, never()).arrangePickup(anyString(), anyString());
    }

    @Test
    void equalCheckoutPaymentsFundTheCorrectOrdersByUniqueReference() {
        User vendor = createCreator("multi-checkout-vendor@example.com", "2130202020");
        PaymentFixture first = createAwaitingPaymentFixture(vendor, OrderFundingMode.PAYSTACK_CHECKOUT);
        PaymentFixture second = createAwaitingPaymentFixture(vendor, OrderFundingMode.PAYSTACK_CHECKOUT);
        OrderPaymentAttempt firstAttempt = createCheckoutAttempt(first.order(), "checkout-equal-first");
        OrderPaymentAttempt secondAttempt = createCheckoutAttempt(second.order(), "checkout-equal-second");
        when(shippingProvider.arrangePickup(
                first.shipment().getProviderShipmentId(),
                first.quote().getProviderRateId()
        )).thenReturn(confirmedShipment(first.shipment()));
        when(shippingProvider.arrangePickup(
                second.shipment().getProviderShipmentId(),
                second.quote().getProviderRateId()
        )).thenReturn(confirmedShipment(second.shipment()));

        paymentService.handleEvent(checkoutEvent(
                "checkout-equal-event-second",
                secondAttempt.getProviderReference(),
                FUNDED_AT
        ));
        paymentService.handleEvent(checkoutEvent(
                "checkout-equal-event-first",
                firstAttempt.getProviderReference(),
                FUNDED_AT.plusSeconds(1)
        ));

        Order fundedFirst = orderRepository.findById(first.order().getId()).orElseThrow();
        Order fundedSecond = orderRepository.findById(second.order().getId()).orElseThrow();
        assertThat(fundedFirst.getPaymentStatus()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(fundedSecond.getPaymentStatus()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(fundedFirst.getStatus()).isEqualTo(OrderStatus.READY_FOR_DELIVERY);
        assertThat(fundedSecond.getStatus()).isEqualTo(OrderStatus.READY_FOR_DELIVERY);
        assertThat(receiptByEventKey("checkout-equal-event-first").getOrder().getId())
                .isEqualTo(first.order().getId());
        assertThat(receiptByEventKey("checkout-equal-event-second").getOrder().getId())
                .isEqualTo(second.order().getId());
        assertThat(paymentAttemptRepository.findAll())
                .allSatisfy(attempt -> assertThat(attempt.getStatus())
                        .isEqualTo(PaymentAttemptStatus.SUCCESS));
        assertThat(walletRepository.findByUser_Id(vendor.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("0.00");
        verify(shippingProvider, times(2)).arrangePickup(anyString(), anyString());
    }

    @Test
    void dvaTransferCreditsWalletWhileCheckoutOrdersArePayable() {
        User vendor = createCreator("checkout-top-up-vendor@example.com", "2140202020");
        PaymentFixture checkout = createAwaitingPaymentFixture(vendor, OrderFundingMode.PAYSTACK_CHECKOUT);
        createCheckoutAttempt(checkout.order(), "checkout-awaiting-top-up");

        paymentService.handleEvent(paymentEvent(
                "checkout-owner-top-up-event",
                "checkout-owner-top-up-reference",
                "2140202020",
                new BigDecimal("25000.00"),
                FUNDED_AT
        ));

        assertThat(receiptByEventKey("checkout-owner-top-up-event").getClassification())
                .isEqualTo(PaymentReceiptClassification.WALLET_CREDITED);
        assertThat(walletRepository.findByUser_Id(vendor.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("25000.00");
        assertAwaitingAndUnfunded(checkout.order().getId());
        assertThat(paymentAttemptRepository.findAll())
                .singleElement()
                .satisfies(attempt -> assertThat(attempt.getStatus())
                        .isEqualTo(PaymentAttemptStatus.INITIALIZED));
        verify(shippingProvider, never()).arrangePickup(anyString(), anyString());
    }

    @Test
    void lateSuccessOnFailedCheckoutFundsOnceAndSupersedesTheReplacement() {
        User vendor = createCreator("checkout-retry-vendor@example.com", "2150202020");
        PaymentFixture fixture = createAwaitingPaymentFixture(vendor, OrderFundingMode.PAYSTACK_CHECKOUT);
        OrderPaymentAttempt first = createCheckoutAttempt(fixture.order(), "checkout-retry-first");

        paymentService.handleEvent(new PaymentStatusUpdatedEvent(
                "checkout-retry-first-event",
                first.getProviderReference(),
                PaymentTransactionStatus.FAILED,
                TOTAL_AMOUNT,
                CURRENCY,
                "card",
                null,
                FUNDED_AT,
                true
        ));
        OrderPaymentAttempt replacement = createCheckoutAttempt(
                fixture.order(),
                "checkout-retry-replacement"
        );
        when(shippingProvider.arrangePickup(anyString(), anyString()))
                .thenReturn(confirmedShipment(fixture.shipment()));

        paymentService.handleEvent(checkoutEvent(
                "checkout-retry-first-event",
                first.getProviderReference(),
                FUNDED_AT.plusSeconds(30)
        ));
        assertThat(paymentAttemptRepository.findById(replacement.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentAttemptStatus.SUPERSEDED);
        paymentService.handleEvent(checkoutEvent(
                "checkout-retry-replacement-event",
                replacement.getProviderReference(),
                FUNDED_AT.plusSeconds(60)
        ));

        Order funded = orderRepository.findById(fixture.order().getId()).orElseThrow();
        assertThat(funded.getPaymentStatus()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(funded.getFundedAt()).isEqualTo(FUNDED_AT.plusSeconds(30));
        assertThat(funded.getStatus()).isEqualTo(OrderStatus.READY_FOR_DELIVERY);
        assertThat(receiptByEventKey("checkout-retry-first-event:FAILED").getClassification())
                .isEqualTo(PaymentReceiptClassification.FAILED);
        assertThat(receiptByEventKey("checkout-retry-first-event").getClassification())
                .isEqualTo(PaymentReceiptClassification.MATCHED);
        assertThat(receiptByEventKey("checkout-retry-replacement-event").getClassification())
                .isEqualTo(PaymentReceiptClassification.DUPLICATE);
        assertThat(paymentAttemptRepository.findById(replacement.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentAttemptStatus.SUCCESS);
        assertThat(receiptRepository.count()).isEqualTo(3);
        verify(shippingProvider, times(1)).arrangePickup(anyString(), anyString());
    }

    @Test
    @WithMockUser(username = BUYER_CREATOR_EMAIL, roles = "USER")
    void checkoutOrderCanCancelBeforeInitializationButNotAfter() {
        User vendor = createCreator(BUYER_CREATOR_EMAIL, "2160202020");
        PaymentFixture notInitialized = createAwaitingPaymentFixture(
                vendor,
                OrderFundingMode.PAYSTACK_CHECKOUT
        );
        PaymentFixture initialized = createAwaitingPaymentFixture(
                vendor,
                OrderFundingMode.PAYSTACK_CHECKOUT
        );
        createCheckoutAttempt(initialized.order(), "checkout-cancellation-blocked");
        when(shippingProvider.cancelShipment(notInitialized.shipment().getProviderShipmentId()))
                .thenReturn(new ShipmentResponse(
                        notInitialized.shipment().getProviderShipmentId(),
                        ShipmentStatus.CANCELLED,
                        notInitialized.shipment().getTrackingReference(),
                        notInitialized.shipment().getTrackingUrl(),
                        Instant.now()
                ));

        shippingService.cancelBeforePayment(notInitialized.order().getId());

        assertThat(orderRepository.findById(notInitialized.order().getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
        assertThatThrownBy(() -> shippingService.cancelBeforePayment(initialized.order().getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("An initialized checkout requires manual review before cancellation");
        assertThat(orderRepository.findById(initialized.order().getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.AWAITING_PAYMENT);
    }

    @Test
    void overpaidDirectOrderStopsShowingGuestPaymentInstructions() {
        User creator = createCreator("overpaid-guest-status@example.com", "2220202020");
        PaymentFixture fixture = createAwaitingPaymentFixture(creator);
        OrderCounterparty buyer = createVerifiedCounterparty(fixture.order(), null);
        GuestOrderAccessService.IssuedGuestToken token = guestAccessService.issue(fixture.order(), buyer);

        paymentService.handleEvent(paymentEvent(
                "overpaid-guest-status-event",
                "overpaid-guest-status-reference",
                "2220202020",
                TOTAL_AMOUNT.add(new BigDecimal("1.00")),
                FUNDED_AT
        ));

        var status = guestStatusService.getStatus(fixture.order().getShareToken(), token.token());
        assertThat(status.status()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        assertThat(status.paymentStatus()).isEqualTo(PaymentStatus.OVERPAID);
        assertThat(status.paymentInstructions()).isNull();
    }

    @Test
    void persistedShipmentBookingStateRecoversACommitToListenerCrashGap() {
        User creator = createCreator("shipping-recovery@example.com", "2121212121");
        PaymentFixture fixture = createAwaitingPaymentFixture(creator);
        Order order = fixture.order();
        order.setPaymentStatus(PaymentStatus.CONFIRMED);
        order.setStatus(OrderStatus.SHIPMENT_BOOKING);
        orderRepository.save(order);
        when(shippingProvider.arrangePickup(anyString(), anyString()))
                .thenReturn(confirmedShipment(fixture.shipment()));

        fundedListener.reconcileShippingBookings();

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.READY_FOR_DELIVERY);
        assertThat(shipmentRepository.findById(fixture.shipment().getId()).orElseThrow().getStatus())
                .isEqualTo(ShipmentStatus.CONFIRMED);
        verify(shippingProvider, times(1)).arrangePickup(anyString(), anyString());
    }

    @Test
    void shipmentBookingPreflightRecoversRemoteSuccessWithoutRedispatch() {
        User creator = createCreator("shipping-preflight@example.com", "2121212122");
        PaymentFixture fixture = createAwaitingPaymentFixture(creator);
        Order order = fixture.order();
        order.setPaymentStatus(PaymentStatus.CONFIRMED);
        order.setStatus(OrderStatus.SHIPMENT_BOOKING);
        orderRepository.save(order);
        when(shippingProvider.trackShipment(fixture.shipment().getProviderShipmentId()))
                .thenReturn(new ShipmentTrackingStatus(
                        ShipmentStatus.CONFIRMED,
                        FUNDED_AT.plusSeconds(60),
                        List.of()
                ));

        fundedListener.reconcileShippingBookings();

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.READY_FOR_DELIVERY);
        assertThat(shipmentRepository.findById(fixture.shipment().getId()).orElseThrow().getStatus())
                .isEqualTo(ShipmentStatus.CONFIRMED);
        verify(shippingProvider, never()).arrangePickup(anyString(), anyString());
    }

    @Test
    void repeatedShipmentBookingFailuresEscalateToManualReview() {
        User creator = createCreator("shipping-exhausted@example.com", "2121212123");
        PaymentFixture fixture = createAwaitingPaymentFixture(creator);
        Order order = fixture.order();
        order.setPaymentStatus(PaymentStatus.CONFIRMED);
        order.setStatus(OrderStatus.SHIPMENT_BOOKING);
        orderRepository.save(order);
        when(shippingProvider.arrangePickup(anyString(), anyString()))
                .thenThrow(new BusinessException("permanent booking failure"));

        for (int attempt = 1; attempt <= 5; attempt++) {
            shippingService.arrangePickup(order.getId());
            if (attempt < 5) {
                OrderShipment retryable = shipmentRepository.findById(fixture.shipment().getId())
                        .orElseThrow();
                retryable.setNextBookingAttemptAt(Instant.now().minusSeconds(1));
                shipmentRepository.save(retryable);
            }
        }

        Order escalated = orderRepository.findById(order.getId()).orElseThrow();
        OrderShipment failed = shipmentRepository.findById(fixture.shipment().getId()).orElseThrow();
        assertThat(escalated.getStatus()).isEqualTo(OrderStatus.MANUAL_REVIEW);
        assertThat(failed.getBookingAttempt()).isEqualTo(5);
        assertThat(failed.getNextBookingAttemptAt()).isNull();
        assertThat(failed.getFailureReason()).isNotBlank();
        verify(shippingProvider, times(5)).arrangePickup(anyString(), anyString());
    }

    @Test
    void ambiguousShipmentDispatchIsVerifiedBeforeAnyRetry() {
        User creator = createCreator("shipping-ambiguous@example.com", "2221212121");
        PaymentFixture fixture = createAwaitingPaymentFixture(creator);
        Order order = fixture.order();
        order.setPaymentStatus(PaymentStatus.CONFIRMED);
        order.setStatus(OrderStatus.SHIPMENT_BOOKING);
        orderRepository.save(order);
        when(shippingProvider.arrangePickup(anyString(), anyString()))
                .thenThrow(new BusinessException("ambiguous provider failure"));

        shippingService.arrangePickup(order.getId());
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.SHIPPING_FAILED);

        when(shippingProvider.trackShipment(fixture.shipment().getProviderShipmentId()))
                .thenReturn(new ShipmentTrackingStatus(
                        ShipmentStatus.CONFIRMED,
                        FUNDED_AT.plusSeconds(60),
                        List.of()
                ));
        OrderShipment retryable = shipmentRepository.findById(fixture.shipment().getId()).orElseThrow();
        retryable.setNextBookingAttemptAt(Instant.now().minusSeconds(1));
        shipmentRepository.save(retryable);
        fundedListener.reconcileShippingBookings();

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.READY_FOR_DELIVERY);
        assertThat(shipmentRepository.findById(fixture.shipment().getId()).orElseThrow().getStatus())
                .isEqualTo(ShipmentStatus.CONFIRMED);
        verify(shippingProvider, times(1)).arrangePickup(anyString(), anyString());
        verify(shippingProvider, times(2)).trackShipment(fixture.shipment().getProviderShipmentId());
    }

    @Test
    void successfulPartialTransfersAggregateToExactTotalAndFundAndBookOnce() {
        User creator = createCreator("partial-payments@example.com", "2323232323");
        PaymentFixture fixture = createAwaitingPaymentFixture(creator);
        when(shippingProvider.arrangePickup(anyString(), anyString()))
                .thenReturn(confirmedShipment(fixture.shipment()));

        paymentService.handleEvent(paymentEvent(
                "payment-event-partial-one",
                "pay-reference-partial-one",
                "2323232323",
                new BigDecimal("50000.00"),
                FUNDED_AT
        ));

        Order underpaid = orderRepository.findById(fixture.order().getId()).orElseThrow();
        assertThat(underpaid.getPaymentStatus()).isEqualTo(PaymentStatus.UNDERPAID);
        assertThat(underpaid.getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);

        paymentService.handleEvent(paymentEvent(
                "payment-event-partial-two",
                "pay-reference-partial-two",
                "2323232323",
                new BigDecimal("81500.00"),
                FUNDED_AT.plusSeconds(30)
        ));

        Order funded = orderRepository.findById(fixture.order().getId()).orElseThrow();
        assertThat(receiptRepository.count()).isEqualTo(2);
        assertThat(receiptByEventKey("payment-event-partial-one").getClassification())
                .isEqualTo(PaymentReceiptClassification.UNDERPAID);
        assertThat(receiptByEventKey("payment-event-partial-two").getClassification())
                .isEqualTo(PaymentReceiptClassification.MATCHED);
        assertThat(receiptRepository.sumAmountByOrderAndClassificationIn(
                funded.getId(),
                countedReceiptClassifications()
        )).isEqualByComparingTo(TOTAL_AMOUNT);
        assertThat(funded.getPaymentStatus()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(funded.getFundedAt()).isEqualTo(FUNDED_AT.plusSeconds(30));
        assertThat(funded.getStatus()).isEqualTo(OrderStatus.READY_FOR_DELIVERY);
        assertThat(quoteRepository.findById(fixture.quote().getId()).orElseThrow().getStatus())
                .isEqualTo(ShippingQuoteStatus.USED);
        verify(shippingProvider, times(1)).arrangePickup(
                fixture.shipment().getProviderShipmentId(),
                fixture.quote().getProviderRateId()
        );
    }

    @Test
    void failedEventAfterSuccessfulUnderpaymentDoesNotEraseUnderpaidState() {
        User creator = createCreator("underpaid-then-failed@example.com", "2424242424");
        PaymentFixture fixture = createAwaitingPaymentFixture(creator);

        paymentService.handleEvent(paymentEvent(
                "payment-event-underpaid-first",
                "pay-reference-underpaid-first",
                "2424242424",
                new BigDecimal("50000.00"),
                FUNDED_AT
        ));
        paymentService.handleEvent(paymentEvent(
                "payment-event-failed-later",
                "pay-reference-failed-later",
                "2424242424",
                new BigDecimal("81500.00"),
                FUNDED_AT.plusSeconds(30),
                PaymentTransactionStatus.FAILED
        ));

        Order underpaid = orderRepository.findById(fixture.order().getId()).orElseThrow();
        assertThat(receiptRepository.count()).isEqualTo(2);
        assertThat(receiptByEventKey("payment-event-failed-later").getClassification())
                .isEqualTo(PaymentReceiptClassification.FAILED);
        assertThat(receiptRepository.sumAmountByOrderAndClassificationIn(
                underpaid.getId(),
                countedReceiptClassifications()
        )).isEqualByComparingTo("50000.00");
        assertThat(underpaid.getPaymentStatus()).isEqualTo(PaymentStatus.UNDERPAID);
        assertThat(underpaid.getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        assertThat(underpaid.getFundedAt()).isNull();
        assertThat(quoteRepository.findById(fixture.quote().getId()).orElseThrow().getStatus())
                .isEqualTo(ShippingQuoteStatus.SELECTED);
        verify(shippingProvider, never()).arrangePickup(anyString(), anyString());
    }

    @ParameterizedTest(name = "successful {1} receipt prevents direct cancellation")
    @CsvSource({
            "1.00, UNDERPAID",
            "131500.01, OVERPAID"
    })
    @WithMockUser(username = BUYER_CREATOR_EMAIL, roles = "USER")
    void anySuccessfulOutOfBalanceReceiptPreventsDirectCancellation(
            String amount,
            PaymentStatus expectedPaymentStatus
    ) {
        User creator = createCreator(BUYER_CREATOR_EMAIL, "2525252525");
        PaymentFixture fixture = createAwaitingPaymentFixture(creator);

        paymentService.handleEvent(paymentEvent(
                "payment-event-" + expectedPaymentStatus.name().toLowerCase(),
                "pay-reference-" + expectedPaymentStatus.name().toLowerCase(),
                "2525252525",
                new BigDecimal(amount),
                FUNDED_AT
        ));

        assertThat(orderRepository.findById(fixture.order().getId()).orElseThrow().getPaymentStatus())
                .isEqualTo(expectedPaymentStatus);
        assertThatThrownBy(() -> shippingService.cancelBeforePayment(fixture.order().getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Orders with received funds require manual refund review");
        assertThat(orderRepository.findById(fixture.order().getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.AWAITING_PAYMENT);
        verify(shippingProvider, never()).cancelShipment(anyString());
    }

    @Test
    @WithMockUser(username = BUYER_CREATOR_EMAIL, roles = "USER")
    void selectedPayableOrderCannotBeCancelledBeforeADelayedTransferArrives() {
        User creator = createCreator(BUYER_CREATOR_EMAIL, "2525252526");
        PaymentFixture fixture = createAwaitingPaymentFixture(creator);

        assertThatThrownBy(() -> shippingService.cancelBeforePayment(fixture.order().getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("A selected payable order requires manual review before cancellation");
        assertThat(orderRepository.findById(fixture.order().getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.AWAITING_PAYMENT);
        verify(shippingProvider, never()).cancelShipment(anyString());
    }

    @ParameterizedTest(name = "{0} shipment cannot be delivery-confirmed")
    @EnumSource(value = ShipmentStatus.class, names = {"FAILED", "CANCELLED"})
    @WithMockUser(username = BUYER_CREATOR_EMAIL, roles = "USER")
    void failedOrCancelledShipmentCannotBeDeliveryConfirmed(ShipmentStatus shipmentStatus) {
        User buyer = createCreator(BUYER_CREATOR_EMAIL, "2626262626");
        Order order = createOrder(
                buyer,
                CreatorRole.BUYER,
                OrderStatus.READY_FOR_DELIVERY,
                PaymentStatus.CONFIRMED
        );
        createVerifiedCounterparty(order, "RCP_INELIGIBLE_SHIPMENT");
        createShipment(order, shipmentStatus);

        assertThatThrownBy(() -> settlementService.confirmCreatorDelivery(
                order.getId(),
                "ineligible-shipment-" + shipmentStatus.name().toLowerCase()
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Shipment is not eligible for delivery confirmation");

        Order unchanged = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(OrderStatus.READY_FOR_DELIVERY);
        assertThat(unchanged.getDeliveryConfirmedAt()).isNull();
        assertThat(settlementRepository.count()).isZero();
        verify(payoutProvider, never()).initiatePayout(any(PayoutRequest.class));
    }

    @Test
    void underpaymentAndOverpaymentAreClassifiedWithoutBookingShipping() {
        User underpaidCreator = createCreator("underpaid@example.com", "3333333333");
        User overpaidCreator = createCreator("overpaid@example.com", "4444444444");
        PaymentFixture underpaid = createAwaitingPaymentFixture(underpaidCreator);
        PaymentFixture overpaid = createAwaitingPaymentFixture(overpaidCreator);

        paymentService.handleEvent(paymentEvent(
                "payment-event-underpaid",
                "pay-reference-underpaid",
                "3333333333",
                new BigDecimal("131499.99"),
                FUNDED_AT
        ));
        paymentService.handleEvent(paymentEvent(
                "payment-event-overpaid",
                "pay-reference-overpaid",
                "4444444444",
                new BigDecimal("131500.01"),
                FUNDED_AT.plusSeconds(1)
        ));

        Order underpaidOrder = orderRepository.findById(underpaid.order().getId()).orElseThrow();
        Order overpaidOrder = orderRepository.findById(overpaid.order().getId()).orElseThrow();
        PaymentReceipt underpaidReceipt = receiptRepository
                .findTopByOrder_IdOrderByCreatedAtDesc(underpaidOrder.getId())
                .orElseThrow();
        PaymentReceipt overpaidReceipt = receiptRepository
                .findTopByOrder_IdOrderByCreatedAtDesc(overpaidOrder.getId())
                .orElseThrow();

        assertThat(underpaidReceipt.getClassification()).isEqualTo(PaymentReceiptClassification.UNDERPAID);
        assertThat(overpaidReceipt.getClassification()).isEqualTo(PaymentReceiptClassification.OVERPAID);
        assertThat(underpaidOrder.getPaymentStatus()).isEqualTo(PaymentStatus.UNDERPAID);
        assertThat(overpaidOrder.getPaymentStatus()).isEqualTo(PaymentStatus.OVERPAID);
        assertThat(underpaidOrder.getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        assertThat(overpaidOrder.getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        assertThat(underpaidOrder.getFundedAt()).isNull();
        assertThat(overpaidOrder.getFundedAt()).isNull();
        assertThat(quoteRepository.findById(underpaid.quote().getId()).orElseThrow().getStatus())
                .isEqualTo(ShippingQuoteStatus.SELECTED);
        assertThat(quoteRepository.findById(overpaid.quote().getId()).orElseThrow().getStatus())
                .isEqualTo(ShippingQuoteStatus.SELECTED);
        verify(shippingProvider, never()).arrangePickup(anyString(), anyString());
    }

    @Test
    void unmatchedCurrencyDoesNotContributeToCumulativePayment() {
        User creator = createCreator("currency-mismatch@example.com", "4545454545");
        PaymentFixture fixture = createAwaitingPaymentFixture(creator);
        when(shippingProvider.arrangePickup(anyString(), anyString()))
                .thenReturn(confirmedShipment(fixture.shipment()));

        paymentService.handleEvent(new PaymentStatusUpdatedEvent(
                "payment-event-wrong-currency",
                "pay-reference-wrong-currency",
                PaymentTransactionStatus.SUCCESS,
                new BigDecimal("50000.00"),
                "USD",
                "dedicated_nuban",
                "4545454545",
                FUNDED_AT,
                true
        ));
        paymentService.handleEvent(paymentEvent(
                "payment-event-valid-currency",
                "pay-reference-valid-currency",
                "4545454545",
                TOTAL_AMOUNT,
                FUNDED_AT.plusSeconds(30)
        ));

        Order funded = orderRepository.findById(fixture.order().getId()).orElseThrow();
        assertThat(receiptByEventKey("payment-event-wrong-currency").getClassification())
                .isEqualTo(PaymentReceiptClassification.UNMATCHED);
        assertThat(receiptByEventKey("payment-event-valid-currency").getClassification())
                .isEqualTo(PaymentReceiptClassification.MATCHED);
        assertThat(receiptRepository.sumAmountByOrderAndClassificationIn(
                funded.getId(),
                countedReceiptClassifications()
        )).isEqualByComparingTo(TOTAL_AMOUNT);
        assertThat(funded.getPaymentStatus()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(funded.getStatus()).isEqualTo(OrderStatus.READY_FOR_DELIVERY);
        verify(shippingProvider, times(1)).arrangePickup(anyString(), anyString());
    }

    @Test
    void ambiguousDirectPaymentRemainsUnmatchedWhileUnreservedTransferCreditsWallet() {
        User ambiguousCreator = createCreator("ambiguous@example.com", "5555555555");
        PaymentFixture firstCandidate = createAwaitingPaymentFixture(ambiguousCreator);
        PaymentFixture secondCandidate = createAwaitingPaymentFixture(ambiguousCreator);
        createCreator("no-candidate@example.com", "6666666666");

        paymentService.handleEvent(paymentEvent(
                "payment-event-ambiguous",
                "pay-reference-ambiguous",
                "5555555555",
                TOTAL_AMOUNT,
                FUNDED_AT
        ));
        paymentService.handleEvent(paymentEvent(
                "payment-event-missing",
                "pay-reference-missing",
                "6666666666",
                TOTAL_AMOUNT,
                FUNDED_AT.plusSeconds(1)
        ));

        assertThat(receiptRepository.count()).isEqualTo(2);
        assertThat(receiptByEventKey("payment-event-ambiguous").getClassification())
                .isEqualTo(PaymentReceiptClassification.UNMATCHED);
        assertThat(receiptByEventKey("payment-event-ambiguous").getOrder()).isNull();
        assertThat(receiptByEventKey("payment-event-missing").getClassification())
                .isEqualTo(PaymentReceiptClassification.WALLET_CREDITED);
        assertThat(receiptByEventKey("payment-event-missing").getOrder()).isNull();
        User walletOwner = userRepository.findByEmail("no-candidate@example.com").orElseThrow();
        assertThat(walletRepository.findByUser_Id(walletOwner.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo(TOTAL_AMOUNT);
        assertThat(walletTransactionRepository.findAll())
                .singleElement()
                .satisfies(transaction -> {
                    assertThat(transaction.getTransactionType())
                            .isEqualTo(WalletTransactionType.DVA_CREDIT);
                    assertThat(transaction.getOrder()).isNull();
                });
        assertAwaitingAndUnfunded(firstCandidate.order().getId());
        assertAwaitingAndUnfunded(secondCandidate.order().getId());
        verify(shippingProvider, never()).arrangePickup(anyString(), anyString());
    }

    @Test
    void verifiedGuestBuyerConfirmationCreditsVendorCreatorItemAmountExactlyOnce() {
        User vendor = createCreator("vendor.creator@example.com", "7777777777");
        Wallet migratedWallet = walletRepository.findByUser_Id(vendor.getId()).orElseThrow();
        migratedWallet.setCurrency("ngn");
        walletRepository.save(migratedWallet);
        Order order = createOrder(vendor, CreatorRole.VENDOR, OrderStatus.READY_FOR_DELIVERY,
                PaymentStatus.CONFIRMED);
        createShipment(order, ShipmentStatus.CONFIRMED);
        OrderCounterparty buyer = createVerifiedCounterparty(order, null);
        GuestOrderAccessService.IssuedGuestToken guestToken = guestAccessService.issue(order, buyer);
        AtomicReference<String> deliveredOtp = new AtomicReference<>();
        doAnswer(invocation -> {
            deliveredOtp.set(extractOtp(invocation.getArgument(1, String.class)));
            return null;
        }).when(smsProvider).sendMessage(
                org.mockito.ArgumentMatchers.eq(buyer.getPhoneNumber()),
                org.mockito.ArgumentMatchers.contains("Use it to confirm delivery")
        );

        OtpChallengeResponse challenge = settlementService.requestGuestDeliveryOtp(
                order.getShareToken(),
                guestToken.token()
        );
        String idempotencyKey = "vendor-delivery-confirmation";
        DeliveryConfirmationResponse first = settlementService.confirmGuestDelivery(
                order.getShareToken(),
                guestToken.token(),
                challenge.challengeId(),
                deliveredOtp.get(),
                idempotencyKey
        );
        DeliveryConfirmationResponse repeated = settlementService.confirmGuestDelivery(
                order.getShareToken(),
                guestToken.token(),
                challenge.challengeId(),
                deliveredOtp.get(),
                idempotencyKey
        );

        Order completed = orderRepository.findById(order.getId()).orElseThrow();
        Wallet wallet = walletRepository.findByUser_Id(vendor.getId()).orElseThrow();
        WalletTransaction transaction = walletTransactionRepository
                .findByOrder_IdAndTransactionType(order.getId(), WalletTransactionType.ORDER_RELEASE)
                .orElseThrow();
        var settlement = settlementRepository.findByOrder_Id(order.getId()).orElseThrow();

        assertThat(first.status()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(repeated.status()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(completed.getSettlementStatus()).isEqualTo(SettlementStatus.COMPLETED);
        assertThat(completed.getDeliveryConfirmedAt()).isNotNull();
        assertThat(completed.getCompletedAt()).isNotNull();
        assertThat(wallet.getBalance()).isEqualByComparingTo(ITEM_AMOUNT);
        assertThat(wallet.getBalance()).isNotEqualByComparingTo(TOTAL_AMOUNT);
        assertThat(transaction.getAmount()).isEqualByComparingTo(ITEM_AMOUNT);
        assertThat(transaction.getIdempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(walletTransactionRepository.count()).isOne();
        assertThat(settlementRepository.count()).isOne();
        assertThat(settlement.getSettlementType()).isEqualTo(SettlementType.WALLET_CREDIT);
        assertThat(settlement.getAmount()).isEqualByComparingTo(ITEM_AMOUNT);
        verify(payoutProvider, never()).initiatePayout(any(PayoutRequest.class));
        ArgumentCaptor<String> emailBody = ArgumentCaptor.forClass(String.class);
        verify(emailService, times(1)).sendEmail(
                org.mockito.ArgumentMatchers.eq(vendor.getEmail()),
                org.mockito.ArgumentMatchers.eq("Buyer confirmed delivery - wallet credited"),
                emailBody.capture()
        );
        assertThat(emailBody.getValue())
                .contains("The buyer has confirmed delivery of the item")
                .contains("NGN 125,000.00")
                .contains("Credited to your ShopSecure wallet");
        ArgumentCaptor<String> buyerEmailBody = ArgumentCaptor.forClass(String.class);
        verify(emailService, times(1)).sendEmail(
                org.mockito.ArgumentMatchers.eq(buyer.getEmail()),
                org.mockito.ArgumentMatchers.eq("Thank you for confirming delivery - vendor credited"),
                buyerEmailBody.capture()
        );
        assertThat(buyerEmailBody.getValue())
                .contains("Thank you for confirming delivery")
                .contains("NGN 125,000.00")
                .contains("The vendor has been credited");
        verify(smsProvider, times(1)).sendMessage(
                org.mockito.ArgumentMatchers.eq(vendor.getPhoneNumber()),
                org.mockito.ArgumentMatchers.contains(
                        "The buyer has confirmed delivery. Your ShopSecure wallet has been credited with NGN 125,000.00"
                )
        );
        verify(smsProvider, times(1)).sendMessage(
                org.mockito.ArgumentMatchers.eq(buyer.getPhoneNumber()),
                org.mockito.ArgumentMatchers.contains(
                        "Thank you for confirming delivery. The vendor has been credited with NGN 125,000.00"
                )
        );
    }

    @Test
    @WithMockUser(username = BUYER_CREATOR_EMAIL, roles = "USER")
    void buyerCreatorConfirmationPaysOnlyItemAmountAndSuccessCompletesSettlement() {
        User buyer = createCreator(BUYER_CREATOR_EMAIL, "8888888888");
        Order order = createOrder(buyer, CreatorRole.BUYER, OrderStatus.READY_FOR_DELIVERY,
                PaymentStatus.CONFIRMED);
        createShipment(order, ShipmentStatus.CONFIRMED);
        OrderCounterparty vendor = createVerifiedCounterparty(order, "RCP_VENDOR_PENDING");
        when(payoutProvider.initiatePayout(any(PayoutRequest.class))).thenAnswer(invocation -> {
            PayoutRequest request = invocation.getArgument(0, PayoutRequest.class);
            return new PayoutResponse("TRF_PENDING", request.reference(), PayoutStatus.PENDING);
        });

        DeliveryConfirmationResponse pending = settlementService.confirmCreatorDelivery(
                order.getId(),
                "buyer-delivery-confirmation"
        );

        ArgumentCaptor<PayoutRequest> payoutRequest = ArgumentCaptor.forClass(PayoutRequest.class);
        verify(payoutProvider).initiatePayout(payoutRequest.capture());
        PayoutRequest dispatched = payoutRequest.getValue();
        Order payoutPending = orderRepository.findById(order.getId()).orElseThrow();
        var pendingSettlement = settlementRepository.findByOrder_Id(order.getId()).orElseThrow();

        assertThat(dispatched.recipientId()).isEqualTo("RCP_VENDOR_PENDING");
        assertThat(dispatched.amount()).isEqualByComparingTo(ITEM_AMOUNT);
        assertThat(dispatched.amount()).isNotEqualByComparingTo(TOTAL_AMOUNT);
        assertThat(pending.status()).isEqualTo(OrderStatus.PAYOUT_PENDING);
        assertThat(payoutPending.getStatus()).isEqualTo(OrderStatus.PAYOUT_PENDING);
        assertThat(payoutPending.getSettlementStatus()).isEqualTo(SettlementStatus.PENDING);
        assertThat(pendingSettlement.getStatus()).isEqualTo(SettlementStatus.PENDING);
        assertThat(pendingSettlement.getSettlementType()).isEqualTo(SettlementType.EXTERNAL_PAYOUT);
        assertThat(pendingSettlement.getAmount()).isEqualByComparingTo(ITEM_AMOUNT);
        verify(emailService, never()).sendEmail(
                org.mockito.ArgumentMatchers.eq(vendor.getEmail()),
                org.mockito.ArgumentMatchers.eq("Buyer confirmed delivery - account credited"),
                anyString()
        );

        Instant payoutOccurredAt = FUNDED_AT.plusSeconds(600);
        PayoutStatusUpdatedEvent success = new PayoutStatusUpdatedEvent(
                dispatched.reference(),
                "TRF_PENDING",
                PayoutStatus.SUCCESS,
                payoutOccurredAt,
                true
        );
        settlementService.applyPayoutStatus(success);
        settlementService.applyPayoutStatus(success);

        Order completed = orderRepository.findById(order.getId()).orElseThrow();
        var completedSettlement = settlementRepository.findByOrder_Id(order.getId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(completed.getSettlementStatus()).isEqualTo(SettlementStatus.COMPLETED);
        assertThat(completed.getCompletedAt()).isNotNull();
        assertThat(completedSettlement.getStatus()).isEqualTo(SettlementStatus.COMPLETED);
        assertThat(completedSettlement.getTransferId()).isEqualTo("TRF_PENDING");
        assertThat(completedSettlement.getCompletedAt()).isNotNull();
        assertThat(completedSettlement.getUpdatedAt()).isEqualTo(payoutOccurredAt);
        assertThat(walletTransactionRepository.count()).isZero();
        ArgumentCaptor<String> emailBody = ArgumentCaptor.forClass(String.class);
        verify(emailService, times(1)).sendEmail(
                org.mockito.ArgumentMatchers.eq(vendor.getEmail()),
                org.mockito.ArgumentMatchers.eq("Buyer confirmed delivery - account credited"),
                emailBody.capture()
        );
        assertThat(emailBody.getValue())
                .contains("The buyer has confirmed delivery of the item")
                .contains("NGN 125,000.00")
                .contains("Credited to your verified bank account");
        ArgumentCaptor<String> buyerEmailBody = ArgumentCaptor.forClass(String.class);
        verify(emailService, times(1)).sendEmail(
                org.mockito.ArgumentMatchers.eq(buyer.getEmail()),
                org.mockito.ArgumentMatchers.eq("Thank you for confirming delivery - vendor credited"),
                buyerEmailBody.capture()
        );
        assertThat(buyerEmailBody.getValue())
                .contains("Thank you for confirming delivery")
                .contains("NGN 125,000.00")
                .contains("The vendor has been credited");
        verify(smsProvider, times(1)).sendMessage(
                org.mockito.ArgumentMatchers.eq(vendor.getPhoneNumber()),
                org.mockito.ArgumentMatchers.contains(
                        "The buyer has confirmed delivery. Your account has been credited with NGN 125,000.00"
                )
        );
        verify(smsProvider, times(1)).sendMessage(
                org.mockito.ArgumentMatchers.eq(buyer.getPhoneNumber()),
                org.mockito.ArgumentMatchers.contains(
                        "Thank you for confirming delivery. The vendor has been credited with NGN 125,000.00"
                )
        );
    }

    @Test
    @WithMockUser(username = BUYER_CREATOR_EMAIL, roles = "USER")
    void payoutRequiringActionKeepsOrderPendingForSettlement() {
        User buyer = createCreator(BUYER_CREATOR_EMAIL, "9999999999");
        Order order = createOrder(buyer, CreatorRole.BUYER, OrderStatus.READY_FOR_DELIVERY,
                PaymentStatus.CONFIRMED);
        createShipment(order, ShipmentStatus.CONFIRMED);
        createVerifiedCounterparty(order, "RCP_VENDOR_ACTION");
        when(payoutProvider.initiatePayout(any(PayoutRequest.class))).thenAnswer(invocation -> {
            PayoutRequest request = invocation.getArgument(0, PayoutRequest.class);
            return new PayoutResponse("TRF_ACTION", request.reference(), PayoutStatus.REQUIRES_ACTION);
        });

        DeliveryConfirmationResponse response = settlementService.confirmCreatorDelivery(
                order.getId(),
                "buyer-delivery-requires-action"
        );

        Order pending = orderRepository.findById(order.getId()).orElseThrow();
        var settlement = settlementRepository.findByOrder_Id(order.getId()).orElseThrow();
        assertThat(response.status()).isEqualTo(OrderStatus.PAYOUT_PENDING);
        assertThat(response.settlementStatus()).isEqualTo(SettlementStatus.REQUIRES_ACTION);
        assertThat(pending.getStatus()).isEqualTo(OrderStatus.PAYOUT_PENDING);
        assertThat(pending.getSettlementStatus()).isEqualTo(SettlementStatus.REQUIRES_ACTION);
        assertThat(pending.getCompletedAt()).isNull();
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.REQUIRES_ACTION);
        assertThat(settlement.getCompletedAt()).isNull();
    }

    @Test
    @WithMockUser(username = BUYER_CREATOR_EMAIL, roles = "USER")
    void failedPayoutIsRetriedWithANewProviderReference() {
        User buyer = createCreator(BUYER_CREATOR_EMAIL, "9797979797");
        Order order = createOrder(buyer, CreatorRole.BUYER, OrderStatus.READY_FOR_DELIVERY,
                PaymentStatus.CONFIRMED);
        createShipment(order, ShipmentStatus.CONFIRMED);
        createVerifiedCounterparty(order, "RCP_VENDOR_RETRY");
        AtomicInteger dispatches = new AtomicInteger();
        when(payoutProvider.initiatePayout(any(PayoutRequest.class))).thenAnswer(invocation -> {
            PayoutRequest request = invocation.getArgument(0, PayoutRequest.class);
            int attempt = dispatches.incrementAndGet();
            return new PayoutResponse(
                    "TRF_RETRY_" + attempt,
                    request.reference(),
                    attempt == 1 ? PayoutStatus.FAILED : PayoutStatus.PENDING
            );
        });

        settlementService.confirmCreatorDelivery(order.getId(), "buyer-delivery-retry");
        var failed = settlementRepository.findByOrder_Id(order.getId()).orElseThrow();
        String firstReference = failed.getPayoutReference();
        when(payoutProvider.verifyPayout(firstReference)).thenReturn(new PayoutStatusUpdatedEvent(
                firstReference,
                "TRF_RETRY_1",
                PayoutStatus.FAILED,
                FUNDED_AT.plusSeconds(600),
                true
        ));

        settlementService.reconcileAutomatedPayouts();

        Order retrying = orderRepository.findById(order.getId()).orElseThrow();
        var retried = settlementRepository.findByOrder_Id(order.getId()).orElseThrow();
        assertThat(retried.getPayoutAttempt()).isEqualTo(2);
        assertThat(retried.getPayoutReference()).isNotEqualTo(firstReference);
        assertThat(retried.getPayoutReference()).endsWith("_2");
        assertThat(retried.getTransferId()).isEqualTo("TRF_RETRY_2");
        assertThat(retried.getStatus()).isEqualTo(SettlementStatus.PENDING);
        assertThat(retrying.getStatus()).isEqualTo(OrderStatus.PAYOUT_PENDING);
        assertThat(retrying.getSettlementStatus()).isEqualTo(SettlementStatus.PENDING);
        verify(payoutProvider, times(2)).initiatePayout(any(PayoutRequest.class));
    }

    @Test
    void malformedPayoutDoesNotAbortTheRemainingReconciliationBatch() {
        User firstCreator = createCreator("payout-batch-first@example.com", "9696969696");
        User secondCreator = createCreator("payout-batch-second@example.com", "9595959595");
        Order firstOrder = createOrder(firstCreator, CreatorRole.BUYER, OrderStatus.PAYOUT_PENDING,
                PaymentStatus.CONFIRMED);
        Order secondOrder = createOrder(secondCreator, CreatorRole.BUYER, OrderStatus.PAYOUT_PENDING,
                PaymentStatus.CONFIRMED);
        String firstReference = "shopsecure_order_" + firstOrder.getId() + "_batch";
        String secondReference = "shopsecure_order_" + secondOrder.getId() + "_batch";
        Instant now = Instant.now();
        settlementRepository.save(OrderSettlement.builder()
                .order(firstOrder)
                .settlementType(SettlementType.EXTERNAL_PAYOUT)
                .amount(ITEM_AMOUNT)
                .status(SettlementStatus.PENDING)
                .idempotencyKey("batch-first")
                .payoutReference(firstReference)
                .transferId("TRF_BATCH_FIRST")
                .payoutAttempt(1)
                .createdAt(now)
                .updatedAt(now)
                .build());
        settlementRepository.save(OrderSettlement.builder()
                .order(secondOrder)
                .settlementType(SettlementType.EXTERNAL_PAYOUT)
                .amount(ITEM_AMOUNT)
                .status(SettlementStatus.PENDING)
                .idempotencyKey("batch-second")
                .payoutReference(secondReference)
                .transferId("TRF_BATCH_SECOND")
                .payoutAttempt(1)
                .createdAt(now)
                .updatedAt(now)
                .build());
        when(payoutProvider.verifyPayout(firstReference)).thenReturn(new PayoutStatusUpdatedEvent(
                firstReference,
                "TRF_MISMATCH",
                PayoutStatus.PENDING,
                now.plusSeconds(30),
                true
        ));
        when(payoutProvider.verifyPayout(secondReference)).thenReturn(new PayoutStatusUpdatedEvent(
                secondReference,
                "TRF_BATCH_SECOND",
                PayoutStatus.SUCCESS,
                now.plusSeconds(30),
                true
        ));

        settlementService.reconcileAutomatedPayouts();

        assertThat(settlementRepository.findByOrder_Id(firstOrder.getId()).orElseThrow().getStatus())
                .isEqualTo(SettlementStatus.PENDING);
        assertThat(settlementRepository.findByOrder_Id(secondOrder.getId()).orElseThrow().getStatus())
                .isEqualTo(SettlementStatus.COMPLETED);
        assertThat(orderRepository.findById(secondOrder.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    @WithMockUser(username = BUYER_CREATOR_EMAIL, roles = "USER")
    void reversedPayoutCannotBeCompletedByReplayedSuccess() {
        User buyer = createCreator(BUYER_CREATOR_EMAIL, "9898989898");
        Order order = createOrder(buyer, CreatorRole.BUYER, OrderStatus.READY_FOR_DELIVERY,
                PaymentStatus.CONFIRMED);
        createShipment(order, ShipmentStatus.CONFIRMED);
        createVerifiedCounterparty(order, "RCP_VENDOR_REVERSED");
        when(payoutProvider.initiatePayout(any(PayoutRequest.class))).thenAnswer(invocation -> {
            PayoutRequest request = invocation.getArgument(0, PayoutRequest.class);
            return new PayoutResponse("TRF_REVERSED", request.reference(), PayoutStatus.PENDING);
        });
        settlementService.confirmCreatorDelivery(order.getId(), "buyer-delivery-reversed");
        String reference = settlementRepository.findByOrder_Id(order.getId()).orElseThrow()
                .getPayoutReference();

        settlementService.applyPayoutStatus(new PayoutStatusUpdatedEvent(
                reference,
                "TRF_REVERSED",
                PayoutStatus.REVERSED,
                FUNDED_AT.plusSeconds(600),
                true
        ));
        settlementService.applyPayoutStatus(new PayoutStatusUpdatedEvent(
                reference,
                "TRF_REVERSED",
                PayoutStatus.SUCCESS,
                FUNDED_AT.plusSeconds(300),
                true
        ));

        Order unchanged = orderRepository.findById(order.getId()).orElseThrow();
        var settlement = settlementRepository.findByOrder_Id(order.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(OrderStatus.PAYOUT_FAILED);
        assertThat(unchanged.getSettlementStatus()).isEqualTo(SettlementStatus.REVERSED);
        assertThat(unchanged.getCompletedAt()).isNull();
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.REVERSED);
        assertThat(settlement.getCompletedAt()).isNull();
    }

    @Test
    void deliveredShipmentEventsAloneDoNotSettleEitherSellerPath() {
        User vendorCreator = createCreator("terminal-vendor@example.com", "1010101010");
        User buyerCreator = createCreator("terminal-buyer@example.com", "1212121212");
        Order vendorCreated = createOrder(vendorCreator, CreatorRole.VENDOR,
                OrderStatus.READY_FOR_DELIVERY, PaymentStatus.CONFIRMED);
        Order buyerCreated = createOrder(buyerCreator, CreatorRole.BUYER,
                OrderStatus.READY_FOR_DELIVERY, PaymentStatus.CONFIRMED);
        createVerifiedCounterparty(vendorCreated, null);
        createVerifiedCounterparty(buyerCreated, "RCP_TERMINAL_VENDOR");
        OrderShipment vendorShipment = createShipment(vendorCreated, ShipmentStatus.CONFIRMED);
        OrderShipment buyerShipment = createShipment(buyerCreated, ShipmentStatus.IN_TRANSIT);
        when(shippingProvider.handleWebhookEvent(any(WebhookPayload.class)))
                .thenReturn(
                        new ShipmentStatusUpdatedEvent(
                                vendorShipment.getProviderShipmentId(),
                                ShipmentStatus.DELIVERED,
                                FUNDED_AT.plusSeconds(1000),
                                true
                        ),
                        new ShipmentStatusUpdatedEvent(
                                buyerShipment.getProviderShipmentId(),
                                ShipmentStatus.DELIVERED,
                                FUNDED_AT.plusSeconds(1001),
                                true
                        )
                );

        shippingService.handleWebhook(new WebhookPayload("vendor-delivered", Map.of()));
        shippingService.handleWebhook(new WebhookPayload("buyer-delivered", Map.of()));

        Order unchangedVendorOrder = orderRepository.findById(vendorCreated.getId()).orElseThrow();
        Order unchangedBuyerOrder = orderRepository.findById(buyerCreated.getId()).orElseThrow();
        assertThat(shipmentRepository.findById(vendorShipment.getId()).orElseThrow().getStatus())
                .isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(shipmentRepository.findById(buyerShipment.getId()).orElseThrow().getStatus())
                .isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(unchangedVendorOrder.getStatus()).isEqualTo(OrderStatus.READY_FOR_DELIVERY);
        assertThat(unchangedBuyerOrder.getStatus()).isEqualTo(OrderStatus.READY_FOR_DELIVERY);
        assertThat(unchangedVendorOrder.getSettlementStatus()).isEqualTo(SettlementStatus.NOT_STARTED);
        assertThat(unchangedBuyerOrder.getSettlementStatus()).isEqualTo(SettlementStatus.NOT_STARTED);
        assertThat(settlementRepository.count()).isZero();
        assertThat(walletTransactionRepository.count()).isZero();
        assertThat(walletRepository.findByUser_Id(vendorCreator.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("0.00");
        assertThat(processedWebhookRepository.count()).isEqualTo(2);
        verify(payoutProvider, never()).initiatePayout(any(PayoutRequest.class));
    }

    private User createCreator(String email, String accountNumber) {
        User user = userRepository.save(User.builder()
                .firstname("Test")
                .lastname("Creator")
                .email(email)
                .phoneNumber("2348012345678")
                .password("encoded")
                .role(Role.USER)
                .createdAt(LocalDateTime.now())
                .build());
        Instant now = Instant.now();
        walletRepository.save(Wallet.builder()
                .user(user)
                .balance(BigDecimal.ZERO)
                .currency(CURRENCY)
                .createdAt(now)
                .updatedAt(now)
                .build());
        virtualAccountRepository.save(VirtualAccount.builder()
                .user(user)
                .accountNumber(accountNumber)
                .bankName("Test Bank")
                .accountName("TEST CREATOR")
                .createdAt(now)
                .build());
        return user;
    }

    private PaymentFixture createAwaitingPaymentFixture(User creator) {
        return createAwaitingPaymentFixture(creator, OrderFundingMode.DIRECT_DVA);
    }

    private PaymentFixture createAwaitingPaymentFixture(
            User creator,
            OrderFundingMode fundingMode
    ) {
        Order order = createOrder(
                creator,
                CreatorRole.BUYER,
                OrderStatus.AWAITING_PAYMENT,
                PaymentStatus.PENDING
        );
        order.setFundingMode(fundingMode);
        if (fundingMode == OrderFundingMode.PAYSTACK_CHECKOUT) {
            order.setPaymentReference(null);
        }
        order = orderRepository.save(order);
        OrderShipment shipment = createShipment(order, ShipmentStatus.DRAFT);
        ShippingQuote quote = quoteRepository.save(ShippingQuote.builder()
                .shipment(shipment)
                .providerRateId(unique("rate"))
                .providerInternalId(unique("internal-rate"))
                .carrierName("Test Logistics")
                .serviceDescription("Test delivery")
                .providerAmount(PROVIDER_DELIVERY_FEE)
                .deliveryMarkup(DELIVERY_MARKUP)
                .deliveryFee(DELIVERY_FEE)
                .serviceFee(SERVICE_FEE)
                .buyerTotal(TOTAL_AMOUNT)
                .currency(CURRENCY)
                .pickupEstimate("Today")
                .deliveryEstimate("Tomorrow")
                .status(ShippingQuoteStatus.SELECTED)
                .selectedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .createdAt(Instant.now())
                .build());
        return new PaymentFixture(order, shipment, quote);
    }

    private Order createOrder(
            User creator,
            CreatorRole creatorRole,
            OrderStatus status,
            PaymentStatus paymentStatus
    ) {
        Instant now = Instant.now();
        return orderRepository.save(Order.builder()
                .itemAmount(ITEM_AMOUNT)
                .currency(CURRENCY)
                .itemDescription("Integration test item")
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
                .shareToken(unique("share"))
                .creator(creator)
                .providerDeliveryFee(PROVIDER_DELIVERY_FEE)
                .deliveryMarkup(DELIVERY_MARKUP)
                .deliveryFee(DELIVERY_FEE)
                .serviceFee(SERVICE_FEE)
                .totalAmount(TOTAL_AMOUNT)
                .paymentReference(unique("payment"))
                .paymentStatus(paymentStatus)
                .settlementStatus(SettlementStatus.NOT_STARTED)
                .fundedAt(paymentStatus == PaymentStatus.CONFIRMED ? FUNDED_AT : null)
                .createdAt(LocalDateTime.now())
                .updatedAt(now)
                .build());
    }

    private OrderCounterparty createVerifiedCounterparty(Order order, String payoutRecipientCode) {
        return counterpartyRepository.save(OrderCounterparty.builder()
                .order(order)
                .firstName("Guest")
                .lastName("Counterparty")
                .phoneNumber("2348098765432")
                .email("guest-" + order.getId() + "@example.com")
                .addressLine1("2 Test Street")
                .addressLine2("")
                .city("Lagos")
                .state("Lagos")
                .country("NG")
                .postalCode("100002")
                .payoutRecipientCode(payoutRecipientCode)
                .phoneVerified(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(Instant.now())
                .build());
    }

    private OrderShipment createShipment(Order order, ShipmentStatus status) {
        Instant now = Instant.now();
        return shipmentRepository.save(OrderShipment.builder()
                .order(order)
                .provider("TEST")
                .providerShipmentId(unique("shipment"))
                .status(status)
                .trackingReference(unique("tracking"))
                .trackingUrl("https://tracking.example.test/" + order.getId())
                .providerUpdatedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private PaymentStatusUpdatedEvent paymentEvent(
            String eventId,
            String reference,
            String receiverAccountNumber,
            BigDecimal amount,
            Instant occurredAt
    ) {
        return paymentEvent(
                eventId,
                reference,
                receiverAccountNumber,
                amount,
                occurredAt,
                PaymentTransactionStatus.SUCCESS
        );
    }

    private PaymentStatusUpdatedEvent paymentEvent(
            String eventId,
            String reference,
            String receiverAccountNumber,
            BigDecimal amount,
            Instant occurredAt,
            PaymentTransactionStatus status
    ) {
        return new PaymentStatusUpdatedEvent(
                eventId,
                reference,
                status,
                amount,
                CURRENCY,
                "dedicated_nuban",
                receiverAccountNumber,
                occurredAt,
                true
        );
    }

    private PaymentReceipt receiptByEventKey(String eventKey) {
        List<PaymentReceipt> receipts = receiptRepository.findAll();
        return receipts.stream()
                .filter(receipt -> receipt.getProviderEventKey().equals(eventKey))
                .findFirst()
                .orElseGet(() -> receipts.stream()
                        .filter(receipt -> receipt.getProviderEventKey().startsWith(eventKey + ":"))
                        .findFirst()
                        .orElseThrow());
    }

    private OrderPaymentAttempt createCheckoutAttempt(Order order, String reference) {
        Instant now = Instant.now();
        return paymentAttemptRepository.save(OrderPaymentAttempt.builder()
                .order(order)
                .provider("PAYSTACK")
                .providerReference(reference)
                .authorizationUrl("https://checkout.paystack.test/" + reference)
                .accessCode("access-" + reference)
                .customerEmail("checkout-buyer@example.com")
                .amount(order.getTotalAmount())
                .currency(order.getCurrency())
                .status(PaymentAttemptStatus.INITIALIZED)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private PaymentStatusUpdatedEvent checkoutEvent(
            String eventId,
            String reference,
            Instant occurredAt
    ) {
        return new PaymentStatusUpdatedEvent(
                eventId,
                reference,
                PaymentTransactionStatus.SUCCESS,
                TOTAL_AMOUNT,
                CURRENCY,
                "card",
                null,
                occurredAt,
                true
        );
    }

    private EnumSet<PaymentReceiptClassification> countedReceiptClassifications() {
        return EnumSet.of(
                PaymentReceiptClassification.UNDERPAID,
                PaymentReceiptClassification.MATCHED,
                PaymentReceiptClassification.OVERPAID
        );
    }

    private ShipmentResponse confirmedShipment(OrderShipment shipment) {
        return new ShipmentResponse(
                shipment.getProviderShipmentId(),
                ShipmentStatus.CONFIRMED,
                shipment.getTrackingReference(),
                shipment.getTrackingUrl(),
                FUNDED_AT.plusSeconds(30)
        );
    }

    private void assertAwaitingAndUnfunded(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(order.getFundedAt()).isNull();
    }

    private String extractOtp(String message) {
        Matcher matcher = OTP_PATTERN.matcher(message);
        if (!matcher.find()) {
            throw new AssertionError("SMS did not contain a six-digit verification code");
        }
        return matcher.group(1);
    }

    private String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    private void cleanDatabase() {
        processedWebhookRepository.deleteAllInBatch();
        settlementRepository.deleteAllInBatch();
        walletTransactionRepository.deleteAllInBatch();
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

    private record PaymentFixture(Order order, OrderShipment shipment, ShippingQuote quote) {
    }
}
