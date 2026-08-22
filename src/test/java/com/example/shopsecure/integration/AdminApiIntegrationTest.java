package com.example.shopsecure.integration;

import com.example.shopsecure.ShopsecureApplication;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.provider.payment.PaymentTransactionStatus;
import com.example.shopsecure.provider.shipping.ShipmentStatus;
import com.example.shopsecure.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = {ShopsecureApplication.class, TestConfig.class},
        properties = {
                "spring.datasource.url=jdbc:h2:mem:adminapi;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
                "payout.reconciliation.initial-delay-ms=86400000",
                "wallet.withdrawal.reconciliation.initial-delay-ms=86400000",
                "shipping.booking-reconciliation.initial-delay-ms=86400000"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminApiIntegrationTest {

    private static final String ADMIN_EMAIL = "admin@example.com";
    private static final String MEMBER_EMAIL = "member.sensitive@example.com";
    private static final String PASSWORD = "Secure1!";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private WalletRepository walletRepository;
    @Autowired
    private VirtualAccountRepository virtualAccountRepository;
    @Autowired
    private WithdrawalBankAccountRepository bankAccountRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderCounterpartyRepository counterpartyRepository;
    @Autowired
    private OrderParcelRepository parcelRepository;
    @Autowired
    private OrderShipmentRepository shipmentRepository;
    @Autowired
    private ShippingQuoteRepository shippingQuoteRepository;
    @Autowired
    private OrderSettlementRepository settlementRepository;
    @Autowired
    private OrderPaymentAttemptRepository paymentAttemptRepository;
    @Autowired
    private PaymentReceiptRepository paymentReceiptRepository;
    @Autowired
    private OrderStatusHistoryRepository historyRepository;
    @Autowired
    private WalletWithdrawalRepository withdrawalRepository;
    @Autowired
    private WalletTransactionRepository walletTransactionRepository;
    @Autowired
    private WaybillRepository waybillRepository;
    @Autowired
    private WaybillPartyRepository waybillPartyRepository;
    @Autowired
    private WaybillParcelRepository waybillParcelRepository;
    @Autowired
    private WaybillShipmentRepository waybillShipmentRepository;
    @Autowired
    private WaybillShippingQuoteRepository waybillShippingQuoteRepository;
    @Autowired
    private WaybillInvitationRepository waybillInvitationRepository;
    @Autowired
    private WaybillPaymentAttemptRepository waybillPaymentAttemptRepository;
    @Autowired
    private WaybillPaymentReceiptRepository waybillPaymentReceiptRepository;
    @Autowired
    private WaybillRefundRepository waybillRefundRepository;
    @Autowired
    private WaybillStatusHistoryRepository waybillStatusHistoryRepository;
    @Autowired
    private OtpChallengeRepository otpChallengeRepository;

    private User admin;
    private User member;
    private Wallet memberWallet;
    private Order order;
    private Waybill waybill;
    private Waybill newerWaybill;

    @BeforeEach
    void setUp() {
        LocalDateTime now = LocalDateTime.now();
        admin = userRepository.save(User.builder()
                .firstname("System")
                .lastname("Admin")
                .email(ADMIN_EMAIL)
                .phoneNumber("+2348099991111")
                .password(passwordEncoder.encode(PASSWORD))
                .role(Role.ADMIN)
                .createdAt(now.minusDays(30))
                .emailVerifiedAt(now.minusDays(30))
                .build());
        member = userRepository.save(User.builder()
                .firstname("Member")
                .lastname("Sensitive")
                .email(MEMBER_EMAIL)
                .phoneNumber("+2348012345678")
                .password(passwordEncoder.encode(PASSWORD))
                .role(Role.USER)
                .createdAt(now.minusDays(10))
                .emailVerifiedAt(now.minusDays(10))
                .build());
        userRepository.save(User.builder()
                .firstname("Pending")
                .lastname("Member")
                .email("pending@example.com")
                .phoneNumber("+2348088882222")
                .password(passwordEncoder.encode(PASSWORD))
                .role(Role.USER)
                .createdAt(now.minusDays(1))
                .build());

        Instant instant = Instant.now();
        memberWallet = walletRepository.save(Wallet.builder()
                .user(member)
                .balance(new BigDecimal("5000.00"))
                .currency("NGN")
                .createdAt(instant.minusSeconds(3600))
                .updatedAt(instant)
                .build());
        virtualAccountRepository.save(VirtualAccount.builder()
                .user(member)
                .accountNumber("9000000001")
                .bankName("Test Bank")
                .accountName("Member Sensitive")
                .createdAt(instant)
                .build());
        bankAccountRepository.save(WithdrawalBankAccount.builder()
                .user(member)
                .accountNumberEncrypted("encrypted-bank-secret")
                .accountNumberLast4("9876")
                .bankCode("999")
                .bankName("Test Bank")
                .accountName("Member Sensitive")
                .payoutRecipientCode("recipient-secret")
                .createdAt(instant)
                .updatedAt(instant)
                .build());

        order = orderRepository.save(Order.builder()
                .itemAmount(new BigDecimal("100000.00"))
                .currency("NGN")
                .itemDescription("A sensitive item description used for admin testing")
                .creatorFirstName(member.getFirstname())
                .creatorLastName(member.getLastname())
                .creatorPhoneNumber(member.getPhoneNumber())
                .creatorEmail(member.getEmail())
                .creatorAddressLine1("1 Private Street")
                .creatorAddressLine2("")
                .creatorCity("Lagos")
                .creatorState("Lagos")
                .creatorCountry("NG")
                .creatorPostalCode("100001")
                .creatorRole(CreatorRole.BUYER)
                .fundingMode(OrderFundingMode.WALLET)
                .status(OrderStatus.PAYOUT_FAILED)
                .shareToken("secret-share-token-" + UUID.randomUUID())
                .creator(member)
                .providerDeliveryFee(new BigDecimal("4000.00"))
                .deliveryMarkup(new BigDecimal("1000.00"))
                .deliveryFee(new BigDecimal("5000.00"))
                .serviceFee(new BigDecimal("500.00"))
                .totalAmount(new BigDecimal("105500.00"))
                .paymentReference("payment-reference-" + UUID.randomUUID())
                .paymentStatus(PaymentStatus.CONFIRMED)
                .settlementStatus(SettlementStatus.FAILED)
                .fundedAt(instant.minusSeconds(1800))
                .createdAt(now.minusDays(2))
                .updatedAt(instant)
                .build());
        counterpartyRepository.save(OrderCounterparty.builder()
                .order(order)
                .firstName("Guest")
                .lastName("Vendor")
                .phoneNumber("+2348077774321")
                .email("guest.vendor@example.com")
                .addressLine1("2 Private Road")
                .addressLine2("")
                .city("Abuja")
                .state("FCT")
                .country("NG")
                .postalCode("900001")
                .bankAccountEncrypted("counterparty-encrypted-secret")
                .bankAccountLast4("4321")
                .bankCode("999")
                .bankName("Test Bank")
                .bankAccountName("Guest Vendor")
                .payoutRecipientCode("counterparty-recipient-secret")
                .phoneVerified(true)
                .createdAt(now.minusDays(2))
                .updatedAt(instant)
                .build());
        parcelRepository.save(OrderParcel.builder()
                .order(order)
                .weightKg(new BigDecimal("2.500"))
                .lengthCm(new BigDecimal("20.00"))
                .widthCm(new BigDecimal("15.00"))
                .heightCm(new BigDecimal("10.00"))
                .quantity(1)
                .parcelType("parcel")
                .createdAt(instant)
                .build());
        OrderShipment shipment = shipmentRepository.save(OrderShipment.builder()
                .order(order)
                .provider("STUB")
                .providerShipmentId("shipment-" + UUID.randomUUID())
                .status(ShipmentStatus.FAILED)
                .trackingReference("tracking-sensitive-reference")
                .trackingUrl("https://tracking.example.test/secret")
                .bookingAttempt(2)
                .failureReason("Provider unavailable")
                .createdAt(instant.minusSeconds(1200))
                .updatedAt(instant)
                .build());
        shippingQuoteRepository.save(ShippingQuote.builder()
                .shipment(shipment)
                .providerRateId("rate-" + UUID.randomUUID())
                .carrierName("Test Carrier")
                .serviceDescription("Standard")
                .providerAmount(new BigDecimal("4000.00"))
                .deliveryMarkup(new BigDecimal("1000.00"))
                .deliveryFee(new BigDecimal("5000.00"))
                .serviceFee(new BigDecimal("500.00"))
                .buyerTotal(new BigDecimal("105500.00"))
                .currency("NGN")
                .status(ShippingQuoteStatus.SELECTED)
                .selectedAt(instant.minusSeconds(2000))
                .expiresAt(instant.plusSeconds(3600))
                .createdAt(instant.minusSeconds(2200))
                .build());
        settlementRepository.save(OrderSettlement.builder()
                .order(order)
                .settlementType(SettlementType.EXTERNAL_PAYOUT)
                .amount(new BigDecimal("100000.00"))
                .status(SettlementStatus.FAILED)
                .idempotencyKey("settlement-idempotency-secret")
                .payoutReference("payout-reference-" + UUID.randomUUID())
                .transferId("transfer-123")
                .payoutAttempt(2)
                .failureReason("Transfer failed")
                .createdAt(instant.minusSeconds(900))
                .updatedAt(instant)
                .build());
        paymentAttemptRepository.save(OrderPaymentAttempt.builder()
                .order(order)
                .provider("PAYSTACK")
                .providerReference("checkout-reference-" + UUID.randomUUID())
                .authorizationUrl("https://checkout.example.test/secret-access")
                .accessCode("secret-access-code")
                .customerEmail("guest.vendor@example.com")
                .amount(new BigDecimal("105500.00"))
                .currency("NGN")
                .status(PaymentAttemptStatus.SUCCESS)
                .createdAt(instant.minusSeconds(1900))
                .updatedAt(instant.minusSeconds(1800))
                .completedAt(instant.minusSeconds(1800))
                .build());
        paymentReceiptRepository.save(PaymentReceipt.builder()
                .order(order)
                .providerEventKey("event-" + UUID.randomUUID())
                .providerReference(order.getPaymentReference())
                .receiverAccountNumber("9000000001")
                .amount(new BigDecimal("105500.00"))
                .currency("NGN")
                .channel("bank_transfer")
                .providerStatus(PaymentTransactionStatus.SUCCESS)
                .classification(PaymentReceiptClassification.UNDERPAID)
                .occurredAt(instant.minusSeconds(1800))
                .createdAt(instant.minusSeconds(1800))
                .build());
        historyRepository.save(OrderStatusHistory.builder()
                .order(order)
                .fromStatus(OrderStatus.PAYOUT_PENDING)
                .toStatus(OrderStatus.PAYOUT_FAILED)
                .reason("Provider reported failure")
                .createdAt(instant)
                .build());

        WalletWithdrawal withdrawal = withdrawalRepository.save(WalletWithdrawal.builder()
                .wallet(memberWallet)
                .amount(new BigDecimal("1000.00"))
                .currency("NGN")
                .status(WalletWithdrawalStatus.DISPATCH_UNKNOWN)
                .idempotencyKey("withdrawal-idempotency-secret")
                .reference("withdrawal-reference-" + UUID.randomUUID())
                .payoutRecipientCode("withdrawal-recipient-secret")
                .accountNumberLast4("9876")
                .bankName("Test Bank")
                .accountName("Member Sensitive")
                .dispatchAttempt(1)
                .createdAt(instant.minusSeconds(600))
                .updatedAt(instant)
                .build());
        walletTransactionRepository.save(WalletTransaction.builder()
                .wallet(memberWallet)
                .withdrawal(withdrawal)
                .transactionType(WalletTransactionType.WITHDRAWAL_DEBIT)
                .reference("wallet-transaction-" + UUID.randomUUID())
                .idempotencyKey("wallet-idempotency-secret")
                .amount(new BigDecimal("1000.00"))
                .balanceBefore(new BigDecimal("6000.00"))
                .balanceAfter(new BigDecimal("5000.00"))
                .createdAt(instant)
                .build());

        setUpWaybills(now);
    }

    @Test
    void rejectsAnonymousAndRegularUsers() throws Exception {
        mockMvc.perform(get("/api/admin/dashboard"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/dashboard")
                        .with(user(MEMBER_EMAIL).roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void allowsARealAdminJwtAndReturnsDashboardMetrics() throws Exception {
        String token = adminToken();

        mockMvc.perform(get("/api/admin/dashboard")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.users.total").value(3))
                .andExpect(jsonPath("$.users.verified").value(2))
                .andExpect(jsonPath("$.users.admins").value(1))
                .andExpect(jsonPath("$.orders.total").value(1))
                .andExpect(jsonPath("$.orders.byStatus.PAYOUT_FAILED").value(1))
                .andExpect(jsonPath("$.finance.availableWalletBalance.NGN").value(5000.00))
                .andExpect(jsonPath("$.finance.outstandingWithdrawalAmount.NGN").value(1000.00))
                .andExpect(jsonPath("$.finance.customerObligation.NGN").value(6000.00))
                .andExpect(jsonPath("$.finance.paymentReceiptsRequiringReview.UNDERPAID.NGN")
                        .value(105500.00))
                .andExpect(jsonPath("$.operations.withdrawalAttentionRequired").value(1))
                .andExpect(jsonPath("$.operations.settlementAttentionRequired").value(1))
                .andExpect(jsonPath("$.operations.shippingAttentionRequired").value(1))
                .andExpect(jsonPath("$.operations.anomalousPaymentReceipts").value(1));
    }

    @Test
    void adminCanAuditChowdeckModeWhileEnvironmentHardKillWins() throws Exception {
        mockMvc.perform(get("/api/admin/shipping/chowdeck-control")
                        .with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.configuredMode").value("OFF"))
                .andExpect(jsonPath("$.effectiveMode").value("OFF"))
                .andExpect(jsonPath("$.hardKillActive").value(true))
                .andExpect(jsonPath("$.version").value(0));

        mockMvc.perform(put("/api/admin/shipping/chowdeck-control")
                        .with(user(ADMIN_EMAIL).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mode":"ON",
                                  "expectedVersion":0,
                                  "reason":"Approved same-state rollout"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configuredMode").value("ON"))
                .andExpect(jsonPath("$.effectiveMode").value("OFF"))
                .andExpect(jsonPath("$.hardKillActive").value(true))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.updatedByAdminId").value(admin.getId()));

        mockMvc.perform(get("/api/admin/shipping/chowdeck-control/history")
                        .with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].fromMode").value("OFF"))
                .andExpect(jsonPath("$.content[0].toMode").value("ON"))
                .andExpect(jsonPath("$.content[0].reason").value("Approved same-state rollout"))
                .andExpect(jsonPath("$.content[0].changedByAdminId").value(admin.getId()))
                .andExpect(jsonPath("$.content[0].controlVersion").value(1));

        mockMvc.perform(put("/api/admin/shipping/chowdeck-control")
                        .with(user(ADMIN_EMAIL).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mode":"OFF",
                                  "expectedVersion":0,
                                  "reason":"Stale rollback"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(
                        "Chowdeck control changed; refresh and retry"
                ));
    }

    @Test
    void listsAndFiltersUsersWithoutExposingCredentialsOrFullContactDetails() throws Exception {
        String response = mockMvc.perform(get("/api/admin/users")
                        .param("role", "USER")
                        .param("verified", "true")
                        .with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(member.getId()))
                .andExpect(jsonPath("$.content[0].maskedEmail").value("m***@example.com"))
                .andExpect(jsonPath("$.content[0].maskedPhoneNumber").value("******5678"))
                .andExpect(jsonPath("$.content[0].walletBalance").value(5000.00))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response)
                .doesNotContain(MEMBER_EMAIL)
                .doesNotContain(member.getPhoneNumber())
                .doesNotContain(member.getPassword())
                .doesNotContain("authVersion");
    }

    @Test
    void returnsMaskedUserDetails() throws Exception {
        String response = mockMvc.perform(get("/api/admin/users/{userId}", member.getId())
                        .with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.virtualAccount.maskedAccountNumber").value("******0001"))
                .andExpect(jsonPath("$.withdrawalBankAccount.maskedAccountNumber").value("******9876"))
                .andExpect(jsonPath("$.orderCount").value(1))
                .andExpect(jsonPath("$.withdrawalCount").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response)
                .doesNotContain("9000000001")
                .doesNotContain("encrypted-bank-secret")
                .doesNotContain("recipient-secret");
    }

    @Test
    void returnsOrderOperationsWithoutBearerOrPaymentSecrets() throws Exception {
        String response = mockMvc.perform(get("/api/admin/orders/{orderId}", order.getId())
                        .with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.id").value(order.getId()))
                .andExpect(jsonPath("$.counterparty.maskedEmail").value("g***@example.com"))
                .andExpect(jsonPath("$.counterparty.maskedBankAccountNumber").value("******4321"))
                .andExpect(jsonPath("$.shipment.status").value("FAILED"))
                .andExpect(jsonPath("$.settlement.status").value("FAILED"))
                .andExpect(jsonPath("$.paymentAttempts[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.paymentReceipts[0].maskedReceiverAccountNumber").value("******0001"))
                .andExpect(jsonPath("$.history[0].toStatus").value("PAYOUT_FAILED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response)
                .doesNotContain(order.getShareToken())
                .doesNotContain("counterparty-encrypted-secret")
                .doesNotContain("counterparty-recipient-secret")
                .doesNotContain("secret-access-code")
                .doesNotContain("secret-access")
                .doesNotContain("settlement-idempotency-secret");
    }

    @Test
    void supportsOrderFiltersAndEnforcesPaginationLimits() throws Exception {
        mockMvc.perform(get("/api/admin/orders")
                        .param("status", "PAYOUT_FAILED")
                        .with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get("/api/admin/orders")
                        .param("status", "COMPLETED")
                        .with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/admin/users")
                        .param("size", "101")
                        .with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().isBadRequest());

    }

    @Test
    void allowsAdminsAndRejectsNonAdminsForWaybillVisibility() throws Exception {
        mockMvc.perform(get("/api/admin/waybills")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

        mockMvc.perform(get("/api/admin/waybills"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/waybills")
                        .with(user(MEMBER_EMAIL).roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void filtersAndPaginatesWaybillsNewestInternalIdFirst() throws Exception {
        mockMvc.perform(get("/api/admin/waybills")
                        .param("creatorId", member.getId().toString())
                        .param("status", "REFUND_PENDING")
                        .param("paymentStatus", "REFUND_PENDING")
                        .with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(waybill.getId()))
                .andExpect(jsonPath("$.content[0].maskedCreatorEmail").value("m***@example.com"))
                .andExpect(jsonPath("$.content[0].maskedCreatorPhoneNumber").value("******5678"));

        mockMvc.perform(get("/api/admin/waybills")
                        .param("creatorId", admin.getId().toString())
                        .with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/admin/waybills")
                        .param("page", "0")
                        .param("size", "1")
                        .with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(newerWaybill.getId()));

        mockMvc.perform(get("/api/admin/waybills")
                        .param("page", "1")
                        .param("size", "1")
                        .with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(waybill.getId()));

        mockMvc.perform(get("/api/admin/waybills")
                        .param("size", "101")
                        .with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsMaskedWaybillOperationsWithoutAccessOrCheckoutSecrets() throws Exception {
        String response = mockMvc.perform(get("/api/admin/waybills/{waybillId}", waybill.getId())
                        .with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.waybill.id").value(waybill.getId()))
                .andExpect(jsonPath("$.waybill.status").value("REFUND_PENDING"))
                .andExpect(jsonPath("$.waybill.paymentStatus").value("REFUND_PENDING"))
                .andExpect(jsonPath("$.waybill.totalAmount").value(27800.00))
                .andExpect(jsonPath("$.creator.maskedEmail").value("m***@example.com"))
                .andExpect(jsonPath("$.creator.maskedPhoneNumber").value("******5678"))
                .andExpect(jsonPath("$.parties[0].role").value("RECIPIENT"))
                .andExpect(jsonPath("$.parties[0].maskedEmail").value("r***@example.com"))
                .andExpect(jsonPath("$.parties[0].maskedPhoneNumber").value("******2468"))
                .andExpect(jsonPath("$.parcel.quantity").value(2))
                .andExpect(jsonPath("$.shipment.status").value("CANCELLED"))
                .andExpect(jsonPath("$.quotes[0].status").value("USED"))
                .andExpect(jsonPath("$.paymentAttempts[0].maskedCustomerEmail")
                        .value("p***@example.com"))
                .andExpect(jsonPath("$.paymentReceipts[0].classification").value("MATCHED"))
                .andExpect(jsonPath("$.refunds[0].status").value("PENDING"))
                .andExpect(jsonPath("$.invitations[0].maskedStagedEmail")
                        .value("i***@example.com"))
                .andExpect(jsonPath("$.invitations[0].maskedStagedPhoneNumber")
                        .value("******8642"))
                .andExpect(jsonPath("$.invitations[0].otpSendCount").value(2))
                .andExpect(jsonPath("$.history[0].toStatus").value("REFUND_PENDING"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response)
                .doesNotContain(MEMBER_EMAIL)
                .doesNotContain(member.getPhoneNumber())
                .doesNotContain("recipient.private@example.com")
                .doesNotContain("+2348075312468")
                .doesNotContain("invitee.private@example.com")
                .doesNotContain("+2348064208642")
                .doesNotContain("payer.private@example.com")
                .doesNotContain("waybill-creation-idempotency-secret")
                .doesNotContain("waybill-creation-request-hash-secret")
                .doesNotContain("waybill-cancellation-idempotency-secret")
                .doesNotContain("invitation-token-hash-secret")
                .doesNotContain("invitation-access-token-hash-secret")
                .doesNotContain("waybill-otp-challenge-secret")
                .doesNotContain("waybill-payment-idempotency-secret")
                .doesNotContain("https://checkout.example.test/waybill-secret")
                .doesNotContain("waybill-secret-access-code")
                .doesNotContain("https://tracking.example.test/waybill-secret")
                .doesNotContain(
                        "creationIdempotencyKey",
                        "creationRequestHash",
                        "cancellationIdempotencyKey",
                        "tokenHash",
                        "accessTokenHash",
                        "otpChallengeId",
                        "idempotencyKey",
                        "authorizationUrl",
                        "accessCode"
                );
    }

    @Test
    void returnsNotFoundForUnknownInternalWaybillId() throws Exception {
        mockMvc.perform(get("/api/admin/waybills/{waybillId}", Long.MAX_VALUE)
                        .with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Waybill not found"));
    }

    @Test
    void exposesReadOnlyFinanceAndOperationsPagesToAdmins() throws Exception {
        mockMvc.perform(get("/api/admin/withdrawals")
                        .with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].maskedAccountNumber").value("******9876"));
        mockMvc.perform(get("/api/admin/settlements")
                        .with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("FAILED"));
        mockMvc.perform(get("/api/admin/shipments")
                        .with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("FAILED"));
        mockMvc.perform(get("/api/admin/wallet-transactions")
                        .with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].transactionType").value("WITHDRAWAL_DEBIT"));
        mockMvc.perform(get("/api/admin/payment-attempts")
                        .with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("SUCCESS"));
        mockMvc.perform(get("/api/admin/payment-receipts")
                        .with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].classification").value("UNDERPAID"));
    }

    private String adminToken() throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(ADMIN_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode body = objectMapper.readTree(response);
        return body.path("token").asText();
    }

    private void setUpWaybills(LocalDateTime now) {
        waybill = waybillRepository.save(Waybill.builder()
                .publicId(UUID.randomUUID().toString())
                .creator(member)
                .creationIdempotencyKey("waybill-creation-idempotency-secret")
                .creationRequestHash("waybill-creation-request-hash-secret")
                .creatorRole(WaybillPartyRole.SENDER)
                .payerType(WaybillPayerType.INVITED_PAYER)
                .description("Sensitive waybill parcel description")
                .declaredValue(new BigDecimal("25000.00"))
                .currency("NGN")
                .status(WaybillStatus.REFUND_PENDING)
                .paymentStatus(WaybillPaymentStatus.REFUND_PENDING)
                .providerDeliveryFee(new BigDecimal("2000.00"))
                .deliveryMarkup(new BigDecimal("500.00"))
                .deliveryFee(new BigDecimal("2500.00"))
                .serviceFee(new BigDecimal("300.00"))
                .totalAmount(new BigDecimal("27800.00"))
                .paymentDueAt(now.plusHours(1))
                .fundingPaymentReference("waybill-funding-reference-" + UUID.randomUUID())
                .fundedAt(now.minusHours(2))
                .cancellationIdempotencyKey("waybill-cancellation-idempotency-secret")
                .cancellationReason("Recipient unavailable")
                .cancellationRequestedAt(now.minusHours(1))
                .createdAt(now.minusDays(3))
                .updatedAt(now)
                .build());

        waybillPartyRepository.save(WaybillParty.builder()
                .waybill(waybill)
                .user(member)
                .role(WaybillPartyRole.SENDER)
                .source(WaybillPartySource.ACCOUNT_SNAPSHOT)
                .firstName(member.getFirstname())
                .lastName(member.getLastname())
                .email(member.getEmail())
                .phoneNumber(member.getPhoneNumber())
                .addressLine1("14 Sender Secret Street")
                .city("Lagos")
                .state("Lagos")
                .country("NG")
                .postalCode("100001")
                .detailsCompletedAt(now.minusDays(3))
                .contactVerifiedAt(now.minusDays(3))
                .createdAt(now.minusDays(3))
                .updatedAt(now.minusDays(3))
                .build());
        waybillPartyRepository.save(WaybillParty.builder()
                .waybill(waybill)
                .role(WaybillPartyRole.RECIPIENT)
                .source(WaybillPartySource.INVITEE_PROVIDED)
                .firstName("Recipient")
                .lastName("Private")
                .email("recipient.private@example.com")
                .phoneNumber("+2348075312468")
                .addressLine1("27 Recipient Secret Avenue")
                .addressLine2("Private suite")
                .city("Abuja")
                .state("FCT")
                .country("NG")
                .postalCode("900001")
                .detailsCompletedAt(now.minusDays(2))
                .contactVerifiedAt(now.minusDays(2))
                .createdAt(now.minusDays(3))
                .updatedAt(now.minusDays(2))
                .build());
        waybillParcelRepository.save(WaybillParcel.builder()
                .waybill(waybill)
                .weightKg(new BigDecimal("3.500"))
                .lengthCm(new BigDecimal("30.00"))
                .widthCm(new BigDecimal("20.00"))
                .heightCm(new BigDecimal("15.00"))
                .quantity(2)
                .parcelType("parcel")
                .createdAt(now.minusDays(3))
                .updatedAt(now.minusDays(2))
                .build());

        WaybillShipment waybillShipment = waybillShipmentRepository.save(WaybillShipment.builder()
                .waybill(waybill)
                .provider("TERMINAL_AFRICA")
                .providerShipmentId("waybill-shipment-" + UUID.randomUUID())
                .status(ShipmentStatus.CANCELLED)
                .trackingReference("waybill-tracking-reference")
                .trackingUrl("https://tracking.example.test/waybill-secret")
                .providerUpdatedAt(now.minusMinutes(30))
                .bookingAttempt(2)
                .failureReason("Cancelled by carrier")
                .createdAt(now.minusDays(2))
                .updatedAt(now.minusMinutes(30))
                .build());
        waybillShippingQuoteRepository.save(WaybillShippingQuote.builder()
                .shipment(waybillShipment)
                .providerRateId("waybill-rate-secret-" + UUID.randomUUID())
                .providerInternalId("waybill-provider-internal-secret")
                .carrierName("Test Carrier")
                .carrierLogo("https://carrier.example.test/private-logo")
                .serviceDescription("Same day")
                .providerAmount(new BigDecimal("2000.00"))
                .deliveryMarkup(new BigDecimal("500.00"))
                .deliveryFee(new BigDecimal("2500.00"))
                .serviceFee(new BigDecimal("300.00"))
                .totalAmount(new BigDecimal("27800.00"))
                .currency("NGN")
                .pickupEstimate("Today")
                .deliveryEstimate("Tomorrow")
                .status(ShippingQuoteStatus.USED)
                .selectedAt(now.minusDays(2))
                .expiresAt(now.plusDays(1))
                .createdAt(now.minusDays(2))
                .build());

        OtpChallenge otpChallenge = otpChallengeRepository.save(OtpChallenge.builder()
                .publicId("waybill-otp-challenge-secret")
                .purpose(OtpPurpose.WAYBILL_INVITATION)
                .subject("waybill-invitation-test")
                .phoneNumber("+2348064208642")
                .email("invitee.private@example.com")
                .codeHash("waybill-otp-code-hash-secret")
                .expiresAt(now.plusMinutes(15))
                .attempts(1)
                .createdAt(now.minusHours(1))
                .build());
        WaybillInvitation invitation = waybillInvitationRepository.save(WaybillInvitation.builder()
                .waybill(waybill)
                .kind(WaybillInvitationKind.PARTY_COMPLETION)
                .targetPartyRole(WaybillPartyRole.RECIPIENT)
                .canPay(true)
                .tokenHash("invitation-token-hash-secret")
                .accessTokenHash("invitation-access-token-hash-secret")
                .stagedFirstName("Invitee")
                .stagedLastName("Private")
                .stagedEmail("invitee.private@example.com")
                .stagedPhoneNumber("+2348064208642")
                .otpChallengeId(otpChallenge.getPublicId())
                .otpSendCount(2)
                .otpFailedAttemptCount(1)
                .otpLockedAt(now.minusMinutes(50))
                .expiresAt(now.plusHours(2))
                .verifiedAt(now.minusMinutes(45))
                .detailsCompletedAt(now.minusMinutes(40))
                .paymentCompletedAt(now.minusMinutes(30))
                .createdAt(now.minusHours(1))
                .updatedAt(now.minusMinutes(30))
                .build());
        WaybillPaymentAttempt paymentAttempt = waybillPaymentAttemptRepository.save(
                WaybillPaymentAttempt.builder()
                        .waybill(waybill)
                        .invitation(invitation)
                        .payerType(WaybillPayerType.INVITED_PAYER)
                        .provider("PAYSTACK")
                        .reference("waybill-payment-reference-" + UUID.randomUUID())
                        .idempotencyKey("waybill-payment-idempotency-secret")
                        .authorizationUrl("https://checkout.example.test/waybill-secret")
                        .accessCode("waybill-secret-access-code")
                        .customerEmail("payer.private@example.com")
                        .amount(new BigDecimal("27800.00"))
                        .currency("NGN")
                        .status(WaybillPaymentAttemptStatus.SUCCESS)
                        .providerTransactionId("provider-transaction-123")
                        .createdAt(now.minusHours(2))
                        .updatedAt(now.minusHours(2))
                        .completedAt(now.minusHours(2))
                        .build()
        );
        waybillPaymentReceiptRepository.save(WaybillPaymentReceipt.builder()
                .waybill(waybill)
                .attempt(paymentAttempt)
                .providerEventKey("waybill-event-" + UUID.randomUUID())
                .reference(paymentAttempt.getReference())
                .amount(new BigDecimal("27800.00"))
                .currency("NGN")
                .channel("card")
                .providerStatus(PaymentTransactionStatus.SUCCESS)
                .classification(WaybillPaymentClassification.MATCHED)
                .occurredAt(now.minusHours(2))
                .createdAt(now.minusHours(2))
                .build());
        waybillRefundRepository.save(WaybillRefund.builder()
                .waybill(waybill)
                .paymentAttempt(paymentAttempt)
                .providerRefundId("waybill-refund-id")
                .providerRefundReference("waybill-refund-reference")
                .amount(new BigDecimal("27800.00"))
                .currency("NGN")
                .status(WaybillRefundStatus.PENDING)
                .reason("Shipment cancelled")
                .providerCreatedAt(now.minusMinutes(20))
                .providerUpdatedAt(now.minusMinutes(10))
                .createdAt(now.minusMinutes(20))
                .updatedAt(now.minusMinutes(10))
                .build());
        waybillStatusHistoryRepository.save(WaybillStatusHistory.builder()
                .waybill(waybill)
                .fromStatus(WaybillStatus.CANCELLATION_PENDING)
                .toStatus(WaybillStatus.REFUND_PENDING)
                .reason("Refund queued")
                .createdAt(now.minusMinutes(20))
                .build());

        newerWaybill = waybillRepository.save(Waybill.builder()
                .publicId(UUID.randomUUID().toString())
                .creator(member)
                .creationIdempotencyKey("newer-waybill-" + UUID.randomUUID())
                .creationRequestHash("newer-waybill-request-hash")
                .creatorRole(WaybillPartyRole.RECIPIENT)
                .payerType(WaybillPayerType.CREATOR)
                .description("Completed test waybill")
                .declaredValue(new BigDecimal("10000.00"))
                .currency("NGN")
                .status(WaybillStatus.COMPLETED)
                .paymentStatus(WaybillPaymentStatus.CONFIRMED)
                .providerDeliveryFee(new BigDecimal("1000.00"))
                .deliveryMarkup(BigDecimal.ZERO)
                .deliveryFee(new BigDecimal("1000.00"))
                .serviceFee(new BigDecimal("100.00"))
                .totalAmount(new BigDecimal("11100.00"))
                .fundedAt(now.minusDays(1))
                .completedAt(now)
                .createdAt(now.minusDays(1))
                .updatedAt(now)
                .build());
    }
}
