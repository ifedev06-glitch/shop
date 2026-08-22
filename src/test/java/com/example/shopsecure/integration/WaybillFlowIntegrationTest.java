package com.example.shopsecure.integration;

import com.example.shopsecure.ShopsecureApplication;
import com.example.shopsecure.entity.OtpPurpose;
import com.example.shopsecure.entity.Role;
import com.example.shopsecure.entity.ShippingQuoteStatus;
import com.example.shopsecure.entity.User;
import com.example.shopsecure.entity.Waybill;
import com.example.shopsecure.entity.WaybillInvitation;
import com.example.shopsecure.entity.WaybillInvitationKind;
import com.example.shopsecure.entity.WaybillPartyRole;
import com.example.shopsecure.entity.WaybillPartySource;
import com.example.shopsecure.entity.WaybillPaymentAttempt;
import com.example.shopsecure.entity.WaybillPaymentAttemptStatus;
import com.example.shopsecure.entity.WaybillPaymentClassification;
import com.example.shopsecure.entity.WaybillPaymentStatus;
import com.example.shopsecure.entity.WaybillShippingQuote;
import com.example.shopsecure.entity.WaybillStatus;
import com.example.shopsecure.provider.common.WebhookPayload;
import com.example.shopsecure.provider.payment.PaymentInitResponse;
import com.example.shopsecure.provider.payment.PaymentProvider;
import com.example.shopsecure.provider.payment.PaymentRequest;
import com.example.shopsecure.provider.payment.PaymentStatusUpdatedEvent;
import com.example.shopsecure.provider.payment.PaymentTransactionStatus;
import com.example.shopsecure.provider.payment.PaymentVerificationResult;
import com.example.shopsecure.provider.payment.PayoutProvider;
import com.example.shopsecure.provider.payment.PayoutStatusUpdatedEvent;
import com.example.shopsecure.provider.payment.RefundProvider;
import com.example.shopsecure.provider.payment.RefundStatusUpdatedEvent;
import com.example.shopsecure.provider.shipping.AddressValidationResult;
import com.example.shopsecure.provider.shipping.ShipmentResponse;
import com.example.shopsecure.provider.shipping.ShipmentStatus;
import com.example.shopsecure.provider.shipping.ShipmentTrackingStatus;
import com.example.shopsecure.provider.shipping.ShippingProvider;
import com.example.shopsecure.provider.shipping.ShippingRateOption;
import com.example.shopsecure.repository.OtpChallengeRepository;
import com.example.shopsecure.repository.UserRepository;
import com.example.shopsecure.repository.WaybillInvitationRepository;
import com.example.shopsecure.repository.WaybillEmailNotificationRepository;
import com.example.shopsecure.repository.WaybillParcelRepository;
import com.example.shopsecure.repository.WaybillPartyRepository;
import com.example.shopsecure.repository.WaybillPaymentAttemptRepository;
import com.example.shopsecure.repository.WaybillPaymentReceiptRepository;
import com.example.shopsecure.repository.WaybillRefundRepository;
import com.example.shopsecure.repository.WaybillRepository;
import com.example.shopsecure.repository.WaybillShipmentRepository;
import com.example.shopsecure.repository.WaybillShippingQuoteRepository;
import com.example.shopsecure.repository.WaybillStatusHistoryRepository;
import com.example.shopsecure.service.OtpDeliveryService;
import com.example.shopsecure.service.EmailService;
import com.example.shopsecure.service.WaybillPaymentReconciliationService;
import com.example.shopsecure.service.WaybillPaymentService;
import com.example.shopsecure.entity.WaybillEmailNotificationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = {
                ShopsecureApplication.class,
                TestConfig.class,
                WaybillFlowIntegrationTest.ProviderTestConfig.class
        },
        properties = {
                "spring.datasource.url=jdbc:h2:mem:finalwaybillflow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
                "app.base-url=https://api.waybill.test",
                "app.frontend-base-url=https://frontend.waybill.test",
                "payout.reconciliation.initial-delay-ms=86400000",
                "wallet.withdrawal.reconciliation.initial-delay-ms=86400000",
                "shipping.booking-reconciliation.initial-delay-ms=86400000",
                "waybill.booking-reconciliation.initial-delay-ms=86400000",
                "waybill.payment-reconciliation.initial-delay-ms=86400000",
                "waybill.refund-reconciliation.initial-delay-ms=86400000",
                "waybill.notification.initial-delay-ms=86400000"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WaybillFlowIntegrationTest {

    private static final String PASSWORD = "Secure1!";
    private static final String CREATOR_EMAIL = "creator.waybill@example.com";
    private static final String CREATOR_PHONE = "+2348091112233";
    private static final String OTHER_EMAIL = "other.waybill@example.com";
    private static final String ADMIN_EMAIL = "admin.waybill@example.com";
    private static final String OPPOSITE_EMAIL = "recipient.waybill@example.com";
    private static final String OPPOSITE_PHONE = "+2348022223344";
    private static final String PARTY_EMAIL = "completed.party@example.com";
    private static final String PARTY_PHONE = "+2348033334421";
    private static final String DRAFT_SHIPMENT_ID = "WB-DRAFT-FINAL-1";
    private static final String RATE_ID = "WB-RATE-FINAL-1";
    private static final String FRONTEND_INVITATION_PREFIX =
            "https://frontend.waybill.test/waybills/invitations/";

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderTestConfig {

        @Bean("finalWaybillOtpDeliveryService")
        @Primary
        OtpDeliveryService finalWaybillOtpDeliveryService() {
            return mock(OtpDeliveryService.class);
        }

        @Bean("finalWaybillEmailService")
        @Primary
        EmailService finalWaybillEmailService() {
            return mock(EmailService.class);
        }

        @Bean("finalWaybillPaymentProvider")
        @Primary
        PaymentProvider finalWaybillPaymentProvider() {
            return mock(PaymentProvider.class);
        }

        @Bean("finalWaybillPayoutProvider")
        @Primary
        PayoutProvider finalWaybillPayoutProvider() {
            return mock(PayoutProvider.class);
        }

        @Bean("finalWaybillRefundProvider")
        @Primary
        RefundProvider finalWaybillRefundProvider() {
            return mock(RefundProvider.class);
        }

        @Bean("finalWaybillShippingProvider")
        @Primary
        ShippingProvider finalWaybillShippingProvider() {
            return mock(ShippingProvider.class);
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private OtpDeliveryService otpDeliveryService;
    @Autowired
    private EmailService emailService;
    @Autowired
    private PaymentProvider paymentProvider;
    @Autowired
    private PayoutProvider payoutProvider;
    @Autowired
    private RefundProvider refundProvider;
    @Autowired
    private ShippingProvider shippingProvider;
    @Autowired
    private WaybillPaymentReconciliationService paymentReconciliationService;
    @Autowired
    private WaybillPaymentService paymentService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private WaybillRepository waybillRepository;
    @Autowired
    private WaybillPartyRepository partyRepository;
    @Autowired
    private WaybillParcelRepository parcelRepository;
    @Autowired
    private WaybillInvitationRepository invitationRepository;
    @Autowired
    private WaybillEmailNotificationRepository emailNotificationRepository;
    @Autowired
    private WaybillShipmentRepository shipmentRepository;
    @Autowired
    private WaybillShippingQuoteRepository quoteRepository;
    @Autowired
    private WaybillPaymentAttemptRepository attemptRepository;
    @Autowired
    private WaybillPaymentReceiptRepository receiptRepository;
    @Autowired
    private WaybillRefundRepository refundRepository;
    @Autowired
    private WaybillStatusHistoryRepository historyRepository;
    @Autowired
    private OtpChallengeRepository otpChallengeRepository;

    private final AtomicReference<String> deliveredOtp = new AtomicReference<>();
    private User creator;
    private User otherUser;
    private String creatorToken;
    private String otherToken;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        cleanDatabase();
        reset(
                otpDeliveryService,
                emailService,
                paymentProvider,
                payoutProvider,
                refundProvider,
                shippingProvider
        );
        deliveredOtp.set(null);

        when(paymentProvider.initializeTransaction(any(PaymentRequest.class))).thenAnswer(invocation -> {
            PaymentRequest request = invocation.getArgument(0, PaymentRequest.class);
            return new PaymentInitResponse(
                    "https://checkout.waybill.test/" + request.reference(),
                    "access-" + request.reference(),
                    request.reference()
            );
        });
        when(paymentProvider.handleWebhookEvent(any(WebhookPayload.class)))
                .thenReturn(PaymentStatusUpdatedEvent.ignored());
        when(refundProvider.handleRefundWebhookEvent(any(WebhookPayload.class)))
                .thenReturn(RefundStatusUpdatedEvent.ignored());
        when(payoutProvider.handlePayoutWebhookEvent(any(WebhookPayload.class)))
                .thenReturn(PayoutStatusUpdatedEvent.ignored());
        when(shippingProvider.validateAddress(any())).thenAnswer(invocation ->
                new AddressValidationResult(true, invocation.getArgument(0)));
        when(shippingProvider.createQuickShipment(any())).thenAnswer(invocation -> new ShipmentResponse(
                DRAFT_SHIPMENT_ID,
                ShipmentStatus.DRAFT,
                "WB-TRACK-FINAL-1",
                "https://tracking.waybill.test/WB-TRACK-FINAL-1",
                Instant.now()
        ));
        when(shippingProvider.getRates(DRAFT_SHIPMENT_ID, "NGN")).thenReturn(List.of(
                new ShippingRateOption(
                        RATE_ID,
                        "WB-INTERNAL-RATE-FINAL-1",
                        "Deterministic Logistics",
                        null,
                        "Next day delivery",
                        new BigDecimal("4000.00"),
                        "NGN",
                        "Today",
                        "Tomorrow"
                )
        ));
        when(shippingProvider.trackShipment(DRAFT_SHIPMENT_ID)).thenAnswer(invocation ->
                new ShipmentTrackingStatus(
                        DRAFT_SHIPMENT_ID,
                        ShipmentStatus.DRAFT,
                        Instant.now(),
                        List.of(),
                        "WB-TRACK-FINAL-1",
                        "https://tracking.waybill.test/WB-TRACK-FINAL-1"
                ));
        when(shippingProvider.getWalletBalance()).thenReturn(new BigDecimal("1000000.00"));
        when(shippingProvider.arrangePickup(DRAFT_SHIPMENT_ID, RATE_ID)).thenAnswer(invocation ->
                new ShipmentResponse(
                        DRAFT_SHIPMENT_ID,
                        ShipmentStatus.CONFIRMED,
                        "WB-TRACK-FINAL-1",
                        "https://tracking.waybill.test/WB-TRACK-FINAL-1",
                        Instant.now()
                ));
        org.mockito.Mockito.doAnswer(invocation -> {
            deliveredOtp.set(invocation.getArgument(2, String.class));
            return null;
        }).when(otpDeliveryService).send(anyString(), anyString(), anyString(), any(OtpPurpose.class));

        creator = createUser("Final", "Creator", CREATOR_EMAIL, CREATOR_PHONE, Role.USER);
        otherUser = createUser("Other", "Member", OTHER_EMAIL, "+2348055556677", Role.USER);
        createUser("Final", "Admin", ADMIN_EMAIL, "+2348066667788", Role.ADMIN);
        creatorToken = login(CREATOR_EMAIL);
        otherToken = login(OTHER_EMAIL);
        adminToken = login(ADMIN_EMAIL);
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void verifiedCreatorGetsIdempotentOwnedWaybillCrudAndRequiredRouteProtection() throws Exception {
        String request = createRequest("CREATOR", true, "Creator-owned documents");
        String creationKey = "create-both-parties-key";

        JsonNode created = responseJson(mockMvc.perform(authenticated(
                                json(post("/waybills"), request)
                                        .header("Idempotency-Key", creationKey),
                                creatorToken
                        ))
                        .andExpect(status().isCreated())
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                        .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                        .andExpect(header().string("Referrer-Policy", "no-referrer"))
                        .andExpect(jsonPath("$.replayed").value(false))
                        .andExpect(jsonPath("$.details.status").value("AWAITING_QUOTE"))
                        .andExpect(jsonPath("$.details.parties.length()").value(2))
                        .andExpect(jsonPath("$.details.invitations.length()").value(0))
                        .andExpect(jsonPath("$.paymentLink").doesNotExist()));
        String publicId = created.path("details").path("publicId").asText();

        mockMvc.perform(authenticated(
                        json(post("/waybills"), request).header("Idempotency-Key", creationKey),
                        creatorToken
                ))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.details.publicId").value(publicId));
        mockMvc.perform(authenticated(
                        json(post("/waybills"), createRequest(
                                "CREATOR",
                                true,
                                "A changed request body"
                        )).header("Idempotency-Key", creationKey),
                        creatorToken
                ))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "Idempotency key was already used with a different waybill request"
                ));
        mockMvc.perform(authenticated(
                        json(post("/waybills"), createRequest(
                                "CREATOR",
                                false,
                                "Missing recipient"
                        )).header("Idempotency-Key", "creator-missing-recipient"),
                        creatorToken
                ))
                .andExpect(status().isBadRequest());
        mockMvc.perform(authenticated(get("/waybills"), creatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].publicId").value(publicId));
        mockMvc.perform(authenticated(get("/waybills/{publicId}", publicId), creatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(publicId))
                .andExpect(jsonPath("$.parties[0].email").isNotEmpty());
        mockMvc.perform(authenticated(get("/waybills/{publicId}/history", publicId), creatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fromStatus").doesNotExist())
                .andExpect(jsonPath("$[0].toStatus").value("AWAITING_QUOTE"));
        mockMvc.perform(authenticated(get("/waybills/{publicId}", publicId), otherToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(authenticated(get("/waybills/{publicId}/history", publicId), otherToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(authenticated(get("/waybills"), otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        assertAuthenticationRequired(json(post("/waybills"), request)
                .header("Idempotency-Key", "anonymous-key"));
        assertAuthenticationRequired(get("/waybills"));
        mockMvc.perform(authenticated(json(post("/waybills"), request), creatorToken))
                .andExpect(status().isBadRequest());
        mockMvc.perform(authenticated(
                        post("/waybills/{publicId}/payments/checkout", publicId),
                        creatorToken
                ))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/waybills/invitations/not-a-token/status"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/waybills/invitations/not-a-token/payments/checkout")
                        .header("Idempotency-Key", "missing-access"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/waybills/invitations/not-a-token/payments/checkout")
                        .header("X-Waybill-Access-Token", "missing-idempotency"))
                .andExpect(status().isBadRequest());

        assertThat(waybillRepository.count()).isOne();
        assertThat(partyRepository.count()).isEqualTo(2);
        assertThat(historyRepository.count()).isOne();
    }

    @Test
    void paymentOnlyLinkUsesCreatorProvidedPartyAndRotatesVerifiedAccess() throws Exception {
        VerifiedInvitation verified = createAndVerifyRecipientInvitation("payment-access-create");
        Waybill waybill = waybillRepository.findByPublicId(verified.publicId()).orElseThrow();
        assertThat(waybill.getStatus()).isEqualTo(WaybillStatus.AWAITING_PAYMENT);
        assertThat(partyRepository.findByWaybill_IdAndRole(
                        waybill.getId(),
                        WaybillPartyRole.RECIPIENT
                )).get().satisfies(party -> {
                    assertThat(party.getSource()).isEqualTo(WaybillPartySource.CREATOR_PROVIDED);
                    assertThat(party.getEmail()).isEqualTo(OPPOSITE_EMAIL);
                    assertThat(party.getPhoneNumber()).isEqualTo(OPPOSITE_PHONE);
                    assertThat(party.getDetailsCompletedAt()).isNotNull();
                    assertThat(party.getContactVerifiedAt()).isNotNull();
                });

        deliveredOtp.set(null);
        JsonNode recovery = responseJson(mockMvc.perform(
                                post(invitationPath(verified.invitationToken()) + "/access-recovery"))
                        .andExpect(status().isAccepted()));
        String recoveryOtp = deliveredOtp.get();
        JsonNode recovered = responseJson(mockMvc.perform(json(
                                post(invitationPath(verified.invitationToken()) + "/access-recovery/verify"),
                                verifyRequest(recovery.path("challengeId").asText(), recoveryOtp)
                        ))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.accessToken").isNotEmpty()));
        String currentAccessToken = recovered.path("accessToken").asText();
        assertThat(currentAccessToken).isNotEqualTo(verified.accessToken());
        mockMvc.perform(get(invitationPath(verified.invitationToken()) + "/status")
                        .header("X-Waybill-Access-Token", verified.accessToken()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(invitationPath(verified.invitationToken()) + "/status")
                        .header("X-Waybill-Access-Token", currentAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parties.length()").value(2));
    }

    @Test
    void payerLinkReissueSupersedesOpenCheckoutAndReplacementCanPay() throws Exception {
        VerifiedInvitation verified = createAndVerifyRecipientInvitation("recipient-reissue");
        String publicId = verified.publicId();
        String oldToken = verified.invitationToken();
        JsonNode oldCheckout = responseJson(mockMvc.perform(
                                post(invitationPath(oldToken) + "/payments/checkout")
                                        .header("X-Waybill-Access-Token", verified.accessToken())
                                        .header("Idempotency-Key", "old-link-checkout")
                        )
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.attemptStatus").value("INITIALIZED")));
        String oldReference = oldCheckout.path("reference").asText();

        JsonNode reissued = responseJson(mockMvc.perform(authenticated(
                                post("/waybills/{publicId}/invitations/reissue", publicId),
                                creatorToken
                        ))
                        .andExpect(status().isOk())
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                        .andExpect(jsonPath("$.paymentLink").isNotEmpty()));
        String replacementLink = reissued.path("paymentLink").asText();
        String replacementToken = invitationToken(replacementLink);
        assertThat(replacementLink).startsWith(FRONTEND_INVITATION_PREFIX);
        assertThat(replacementToken).isNotEqualTo(oldToken);

        mockMvc.perform(get(invitationPath(oldToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(invitationPath(replacementToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invitationKind").value("PAYMENT_ONLY"))
                .andExpect(jsonPath("$.targetPartyRole").value("RECIPIENT"))
                .andExpect(jsonPath("$.detailsRequired").value(false))
                .andExpect(jsonPath("$.canPay").value(true))
                .andExpect(jsonPath("$.inviteeEmailMasked").value("r***@***"))
                .andExpect(jsonPath("$.inviteePhoneNumberMasked").isNotEmpty());

        WaybillPaymentAttempt superseded = attemptRepository.findByReference(oldReference).orElseThrow();
        assertThat(superseded.getStatus()).isEqualTo(WaybillPaymentAttemptStatus.SUPERSEDED);
        assertThat(superseded.getFailureReason()).isEqualTo("Payment link was replaced");

        deliveredOtp.set(null);
        JsonNode replacementChallenge = responseJson(mockMvc.perform(
                                post(invitationPath(replacementToken) + "/payment-access-otp"))
                        .andExpect(status().isAccepted()));
        JsonNode replacementAccess = responseJson(mockMvc.perform(json(
                                post(invitationPath(replacementToken) + "/verify-otp"),
                                verifyRequest(
                                        replacementChallenge.path("challengeId").asText(),
                                        deliveredOtp.get()
                                )
                        ))
                        .andExpect(status().isOk()));
        mockMvc.perform(post(invitationPath(replacementToken) + "/payments/checkout")
                        .header("X-Waybill-Access-Token", replacementAccess.path("accessToken").asText())
                        .header("Idempotency-Key", "replacement-link-checkout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptStatus").value("INITIALIZED"))
                .andExpect(jsonPath("$.reference").value(org.hamcrest.Matchers.not(oldReference)));

        assertThat(invitationRepository.findAll())
                .hasSize(2)
                .anySatisfy(invitation -> assertThat(invitation.getRevokedAt()).isNotNull())
                .anySatisfy(invitation -> assertThat(invitation.getRevokedAt()).isNull());
    }

    @Test
    void creatorAloneCanSelectAndInitializeCreatorCheckoutWithOneOpenKey() throws Exception {
        JsonNode created = createWaybill(
                createRequest("CREATOR", true, "Creator checkout documents"),
                "creator-checkout-create"
        );
        String publicId = created.path("details").path("publicId").asText();

        JsonNode quotes = responseJson(mockMvc.perform(authenticated(
                                post("/waybills/{publicId}/shipping/quotes", publicId),
                                creatorToken
                        ))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$[0].carrierName").value("Deterministic Logistics"))
                        .andExpect(jsonPath("$[0].deliveryFee").value(5000.00))
                        .andExpect(jsonPath("$[0].totalAmount").value(5500.00)));
        long quoteId = quotes.get(0).path("quoteId").asLong();
        mockMvc.perform(authenticated(
                        post("/waybills/{publicId}/shipping/quotes", publicId),
                        otherToken
                ))
                .andExpect(status().isForbidden());
        mockMvc.perform(authenticated(
                        json(put("/waybills/{publicId}/shipping/selection", publicId),
                                "{\"quoteId\":" + quoteId + "}"),
                        otherToken
                ))
                .andExpect(status().isForbidden());
        mockMvc.perform(authenticated(
                        json(put("/waybills/{publicId}/shipping/selection", publicId),
                                "{\"quoteId\":" + quoteId + "}"),
                        creatorToken
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectedQuote.status").value("SELECTED"))
                .andExpect(jsonPath("$.paymentLink").doesNotExist());
        mockMvc.perform(authenticated(get("/waybills/{publicId}", publicId), creatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_PAYMENT"))
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"));

        mockMvc.perform(authenticated(
                        post("/waybills/{publicId}/payments/checkout", publicId)
                                .header("Idempotency-Key", "other-user-checkout"),
                        otherToken
                ))
                .andExpect(status().isForbidden());
        JsonNode checkout = responseJson(mockMvc.perform(authenticated(
                                post("/waybills/{publicId}/payments/checkout", publicId)
                                        .header("Idempotency-Key", "creator-checkout-key"),
                                creatorToken
                        ))
                        .andExpect(status().isOk())
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                        .andExpect(jsonPath("$.payerRole").value("SENDER"))
                        .andExpect(jsonPath("$.attemptStatus").value("INITIALIZED")));
        String reference = checkout.path("reference").asText();
        assertThat(checkout.path("authorizationUrl").asText())
                .isEqualTo("https://checkout.waybill.test/" + reference);

        mockMvc.perform(authenticated(
                        post("/waybills/{publicId}/payments/checkout", publicId)
                                .header("Idempotency-Key", "creator-checkout-key"),
                        creatorToken
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").value(reference));
        mockMvc.perform(authenticated(
                        post("/waybills/{publicId}/payments/checkout", publicId)
                                .header("Idempotency-Key", "different-open-key"),
                        creatorToken
                ))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "Another waybill payment attempt is open; retry with its Idempotency-Key"
                ));

        assertThat(attemptRepository.findAll()).singleElement().satisfies(attempt -> {
            assertThat(attempt.getPayerType().name()).isEqualTo("CREATOR");
            assertThat(attempt.getStatus()).isEqualTo(WaybillPaymentAttemptStatus.INITIALIZED);
            assertThat(attempt.getIdempotencyKey()).isEqualTo("creator-checkout-key");
        });
        verify(paymentProvider, times(1)).initializeTransaction(argThat(request ->
                request.reference().equals(reference)
                        && request.amount().compareTo(new BigDecimal("5500.00")) == 0
                        && request.customerEmail().equals(CREATOR_EMAIL)
                        && request.callbackUrl().equals("https://api.waybill.test/waybills/" + publicId)
                        && request.metadata().get("resource_type").equals("WAYBILL")
        ));
    }

    @Test
    void invitedCheckoutExactVerificationFundsAndBooksOnceAndAdminOmitsSecrets() throws Exception {
        when(shippingProvider.createQuickShipment(any())).thenReturn(new ShipmentResponse(
                DRAFT_SHIPMENT_ID,
                ShipmentStatus.DRAFT,
                "WB-TRACK-FINAL-1",
                null,
                Instant.now()
        ));
        when(shippingProvider.trackShipment(DRAFT_SHIPMENT_ID)).thenReturn(
                new ShipmentTrackingStatus(
                        DRAFT_SHIPMENT_ID,
                        ShipmentStatus.DRAFT,
                        Instant.now(),
                        List.of(),
                        "WB-TRACK-FINAL-1",
                        null
                ),
                new ShipmentTrackingStatus(
                        DRAFT_SHIPMENT_ID,
                        ShipmentStatus.CONFIRMED,
                        Instant.now(),
                        List.of(),
                        "WB-TRACK-FINAL-1",
                        "https://tracking.waybill.test/WB-TRACK-FINAL-1"
                )
        );
        when(shippingProvider.arrangePickup(DRAFT_SHIPMENT_ID, RATE_ID)).thenReturn(
                new ShipmentResponse(
                        DRAFT_SHIPMENT_ID,
                        ShipmentStatus.CONFIRMED,
                        "WB-TRACK-FINAL-1",
                        null,
                        Instant.now()
                )
        );
        String creationKey = "invited-funded-create-key";
        VerifiedInvitation verified = createAndVerifyRecipientInvitation(creationKey);
        Waybill createdWaybill = waybillRepository.findByPublicId(verified.publicId()).orElseThrow();
        long quoteId = quoteRepository.findByShipment_Waybill_IdAndStatus(
                        createdWaybill.getId(),
                        ShippingQuoteStatus.SELECTED
                )
                .orElseThrow()
                .getId();
        var history = historyRepository.findByWaybill_IdOrderByCreatedAtAscIdAsc(
                createdWaybill.getId()
        );
        assertThat(history.get(history.size() - 1).getReason())
                .isEqualTo("Creator selected shipping quote");

        mockMvc.perform(authenticated(
                        post("/waybills/{publicId}/payments/checkout", verified.publicId())
                                .header("Idempotency-Key", "creator-cannot-pay"),
                        creatorToken
                ))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(invitationPath(verified.invitationToken()) + "/payments/checkout")
                        .header("X-Waybill-Access-Token", "invalid-access")
                        .header("Idempotency-Key", "invalid-access-key"))
                .andExpect(status().isForbidden());

        String checkoutKey = "invited-checkout-key";
        JsonNode checkout = responseJson(mockMvc.perform(
                                post(invitationPath(verified.invitationToken()) + "/payments/checkout")
                                        .header("X-Waybill-Access-Token", verified.accessToken())
                                        .header("Idempotency-Key", checkoutKey)
                        )
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.payerRole").value("RECIPIENT"))
                        .andExpect(jsonPath("$.attemptStatus").value("INITIALIZED")));
        String reference = checkout.path("reference").asText();
        String authorizationUrl = checkout.path("authorizationUrl").asText();
        String accessCode = checkout.path("accessCode").asText();
        mockMvc.perform(post(invitationPath(verified.invitationToken()) + "/payments/checkout")
                        .header("X-Waybill-Access-Token", verified.accessToken())
                        .header("Idempotency-Key", checkoutKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").value(reference));
        mockMvc.perform(post(invitationPath(verified.invitationToken()) + "/payments/checkout")
                        .header("X-Waybill-Access-Token", verified.accessToken())
                        .header("Idempotency-Key", "another-invited-key"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "Another waybill payment attempt is open; retry with its Idempotency-Key"
                ));

        WaybillPaymentAttempt attempt = attemptRepository.findByReference(reference).orElseThrow();
        attempt.setNextRecheckAt(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        attemptRepository.saveAndFlush(attempt);
        Instant paidAt = Instant.now();
        PaymentVerificationResult verification = new PaymentVerificationResult(
                "txn-final-waybill",
                reference,
                PaymentTransactionStatus.SUCCESS,
                new BigDecimal("5500.00"),
                "NGN",
                "card",
                paidAt
        );
        when(paymentProvider.verifyTransaction(reference)).thenReturn(verification);

        paymentReconciliationService.reconcilePayments();
        paymentService.handleVerification(verification);
        PaymentStatusUpdatedEvent duplicateWebhook = new PaymentStatusUpdatedEvent(
                verification.eventId(),
                reference,
                PaymentTransactionStatus.SUCCESS,
                verification.amount(),
                verification.currency(),
                verification.channel(),
                null,
                paidAt,
                true
        );
        when(paymentProvider.handleWebhookEvent(any(WebhookPayload.class))).thenReturn(duplicateWebhook);
        mockMvc.perform(post("/api/webhooks/paystack")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/webhooks/paystack")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        Waybill funded = waybillRepository.findByPublicId(verified.publicId()).orElseThrow();
        assertThat(funded.getPaymentStatus()).isEqualTo(WaybillPaymentStatus.CONFIRMED);
        assertThat(funded.getFundingPaymentReference()).isEqualTo(reference);
        assertThat(funded.getFundedAt()).isNotNull();
        assertThat(funded.getStatus()).isEqualTo(WaybillStatus.READY_FOR_DELIVERY);
        assertThat(attemptRepository.findByReference(reference).orElseThrow().getStatus())
                .isEqualTo(WaybillPaymentAttemptStatus.SUCCESS);
        assertThat(receiptRepository.findAll()).singleElement().satisfies(receipt -> {
            assertThat(receipt.getProviderEventKey()).isEqualTo("txn-final-waybill");
            assertThat(receipt.getClassification()).isEqualTo(WaybillPaymentClassification.MATCHED);
            assertThat(receipt.getProviderStatus()).isEqualTo(PaymentTransactionStatus.SUCCESS);
        });
        assertThat(shipmentRepository.findByWaybill_Id(funded.getId()).orElseThrow()).satisfies(shipment -> {
            assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CONFIRMED);
            assertThat(shipment.getBookingAttempt()).isEqualTo(1);
            assertThat(shipment.getTrackingUrl()).isEqualTo(
                    "https://tracking.waybill.test/WB-TRACK-FINAL-1"
            );
        });
        assertThat(quoteRepository.findById(quoteId).orElseThrow().getStatus())
                .isEqualTo(ShippingQuoteStatus.USED);
        assertThat(invitationRepository.findAll()).singleElement()
                .satisfies(invitation -> assertThat(invitation.getPaymentCompletedAt()).isNotNull());
        verify(paymentProvider, times(1)).initializeTransaction(any(PaymentRequest.class));
        verify(paymentProvider, times(1)).verifyTransaction(reference);
        verify(shippingProvider, times(1)).arrangePickup(DRAFT_SHIPMENT_ID, RATE_ID);
        verify(shippingProvider, times(2)).trackShipment(DRAFT_SHIPMENT_ID);
        verify(emailService, timeout(5000).times(2)).sendEmail(
                anyString(),
                eq("Payment confirmed - package entered pickup stage"),
                argThat(body -> body.contains("https://tracking.waybill.test/WB-TRACK-FINAL-1")),
                argThat(key -> key.startsWith("waybill-awaiting-pickup/"))
        );
        waitForPickupEmails();
        assertThat(emailNotificationRepository.findAll())
                .hasSize(2)
                .allSatisfy(notification -> {
                    assertThat(notification.getStatus()).isEqualTo(WaybillEmailNotificationStatus.SENT);
                    assertThat(notification.getSentAt()).isNotNull();
                });
        assertThat(receiptRepository.count()).isOne();

        String adminList = mockMvc.perform(authenticated(
                                get("/api/admin/waybills")
                                        .param("creatorId", creator.getId().toString()),
                                adminToken
                        ))
                        .andExpect(status().isOk())
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                        .andExpect(jsonPath("$.totalElements").value(1))
                        .andExpect(jsonPath("$.content[0].publicId").value(verified.publicId()))
                        .andReturn().getResponse().getContentAsString();
        String adminDetail = mockMvc.perform(authenticated(
                                get("/api/admin/waybills/{waybillId}", funded.getId()),
                                adminToken
                        ))
                        .andExpect(status().isOk())
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                        .andExpect(jsonPath("$.waybill.publicId").value(verified.publicId()))
                        .andExpect(jsonPath("$.parties.length()").value(2))
                        .andExpect(jsonPath("$.paymentAttempts[0].status").value("SUCCESS"))
                        .andExpect(jsonPath("$.paymentReceipts[0].classification").value("MATCHED"))
                        .andReturn().getResponse().getContentAsString();
        assertThat(adminList + adminDetail)
                .doesNotContain(
                        CREATOR_EMAIL,
                        CREATOR_PHONE,
                        OPPOSITE_EMAIL,
                        OPPOSITE_PHONE,
                        PARTY_EMAIL,
                        PARTY_PHONE,
                        verified.invitationToken(),
                        verified.accessToken(),
                        creationKey,
                        checkoutKey,
                        authorizationUrl,
                        accessCode,
                        "creationIdempotencyKey",
                        "creationRequestHash",
                        "tokenHash",
                        "accessTokenHash",
                        "otpChallengeId",
                        "idempotencyKey",
                        "authorizationUrl",
                        "accessCode"
                );
    }

    @Test
    void payerCheckoutRejectsInvalidAndExpiredAccess() throws Exception {
        VerifiedInvitation verified = createAndVerifyRecipientInvitation("recipient-access-rules");

        mockMvc.perform(post(invitationPath(verified.invitationToken()) + "/payments/checkout")
                        .header("X-Waybill-Access-Token", "invalid-access")
                        .header("Idempotency-Key", "invalid-access"))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));

        WaybillInvitation invitation = invitationRepository.findAll().get(0);
        invitation.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        invitationRepository.saveAndFlush(invitation);
        mockMvc.perform(post(invitationPath(verified.invitationToken()) + "/payments/checkout")
                        .header("X-Waybill-Access-Token", verified.accessToken())
                        .header("Idempotency-Key", "expired-access"))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    @Test
    void creatorCanRefreshAnExpiredUnfundedQuoteSelection() throws Exception {
        VerifiedInvitation verified = createAndVerifyRecipientInvitation("recipient-quote-expiry");
        LocalDateTime expiredAt = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1);
        Waybill waybill = waybillRepository.findByPublicId(verified.publicId()).orElseThrow();
        WaybillShippingQuote selected = quoteRepository.findByShipment_Waybill_IdAndStatus(
                        waybill.getId(),
                        ShippingQuoteStatus.SELECTED
                )
                .orElseThrow();
        long quoteId = selected.getId();
        selected.setExpiresAt(expiredAt);
        quoteRepository.saveAndFlush(selected);
        waybill.setPaymentDueAt(expiredAt);
        waybillRepository.saveAndFlush(waybill);

        mockMvc.perform(authenticated(
                        json(put("/waybills/{publicId}/shipping/selection", verified.publicId()),
                                "{\"quoteId\":" + quoteId + "}"),
                        creatorToken
                ))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "Shipping quote has expired; request fresh rates"
                ));

        JsonNode refreshedQuotes = responseJson(mockMvc.perform(authenticated(
                                post("/waybills/{publicId}/shipping/quotes", verified.publicId()),
                                creatorToken
                        ))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$[0].status").value("AVAILABLE")));
        long refreshedQuoteId = refreshedQuotes.get(0).path("quoteId").asLong();

        Waybill refreshed = waybillRepository.findByPublicId(verified.publicId()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(WaybillStatus.AWAITING_QUOTE);
        assertThat(refreshed.getPaymentStatus()).isEqualTo(WaybillPaymentStatus.NOT_STARTED);
        assertThat(refreshed.getPaymentDueAt()).isNull();
        WaybillShippingQuote refreshedQuote = quoteRepository.findById(refreshedQuoteId).orElseThrow();
        assertThat(refreshedQuote.getExpiresAt()).isAfter(LocalDateTime.now(ZoneOffset.UTC));
        verify(shippingProvider, times(2)).getRates(DRAFT_SHIPMENT_ID, "NGN");

        refreshedQuote.setExpiresAt(expiredAt);
        quoteRepository.saveAndFlush(refreshedQuote);
        mockMvc.perform(authenticated(
                        json(put("/waybills/{publicId}/shipping/selection", verified.publicId()),
                                "{\"quoteId\":" + refreshedQuoteId + "}"),
                        creatorToken
                ))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "Shipping quote has expired; request fresh rates"
                ));
        assertThat(quoteRepository.findById(refreshedQuoteId).orElseThrow().getStatus())
                .isEqualTo(ShippingQuoteStatus.EXPIRED);
    }

    private VerifiedInvitation createAndVerifyRecipientInvitation(String creationKey) throws Exception {
        JsonNode created = createWaybill(
                createRequest("INVITED_PAYER", true, "Invited recipient documents"),
                creationKey
        );
        String publicId = created.path("details").path("publicId").asText();
        JsonNode quotes = responseJson(mockMvc.perform(authenticated(
                                post("/waybills/{publicId}/shipping/quotes", publicId),
                                creatorToken
                        ))
                        .andExpect(status().isOk()));
        JsonNode selection = responseJson(mockMvc.perform(authenticated(
                                json(put("/waybills/{publicId}/shipping/selection", publicId),
                                        "{\"quoteId\":" + quotes.get(0).path("quoteId").asLong() + "}"),
                                creatorToken
                        ))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.selectedQuote.status").value("SELECTED"))
                        .andExpect(jsonPath("$.paymentLink").isNotEmpty()));
        String invitationLink = selection.path("paymentLink").asText();
        String invitationToken = invitationToken(invitationLink);
        assertThat(invitationLink).startsWith(FRONTEND_INVITATION_PREFIX);

        String summary = mockMvc.perform(get(invitationPath(invitationToken)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.invitationKind").value("PAYMENT_ONLY"))
                .andExpect(jsonPath("$.targetPartyRole").value("RECIPIENT"))
                .andExpect(jsonPath("$.payerRole").value("RECIPIENT"))
                .andExpect(jsonPath("$.detailsRequired").value(false))
                .andExpect(jsonPath("$.canPay").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(summary)
                .doesNotContain(CREATOR_EMAIL)
                .doesNotContain(CREATOR_PHONE)
                .doesNotContain(OPPOSITE_EMAIL)
                .doesNotContain(OPPOSITE_PHONE);

        deliveredOtp.set(null);
        JsonNode challenge = responseJson(mockMvc.perform(
                                post(invitationPath(invitationToken) + "/payment-access-otp"))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.partyRole").value("RECIPIENT"))
                        .andExpect(jsonPath("$.emailMasked").value("r***@***")));
        String otp = deliveredOtp.get();
        assertThat(otp).matches("\\d{6}");
        verify(otpDeliveryService).send(
                OPPOSITE_PHONE,
                OPPOSITE_EMAIL,
                otp,
                OtpPurpose.WAYBILL_INVITATION
        );
        JsonNode access = responseJson(mockMvc.perform(json(
                                post(invitationPath(invitationToken) + "/verify-otp"),
                                verifyRequest(challenge.path("challengeId").asText(), otp)
                        ))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.invitationKind").value("PAYMENT_ONLY"))
                        .andExpect(jsonPath("$.targetPartyRole").value("RECIPIENT"))
                        .andExpect(jsonPath("$.detailsCompleted").value(true))
                        .andExpect(jsonPath("$.parties.length()").value(2))
                        .andExpect(jsonPath("$.accessToken").isNotEmpty()));
        String accessBody = access.toString();
        assertThat(accessBody)
                .doesNotContain(CREATOR_EMAIL)
                .doesNotContain(OPPOSITE_EMAIL)
                .doesNotContain(PARTY_EMAIL);
        return new VerifiedInvitation(publicId, invitationToken, access.path("accessToken").asText());
    }

    private JsonNode createWaybill(String request, String idempotencyKey) throws Exception {
        return responseJson(mockMvc.perform(authenticated(
                                json(post("/waybills"), request)
                                        .header("Idempotency-Key", idempotencyKey),
                                creatorToken
                        ))
                        .andExpect(status().isCreated())
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store")));
    }

    private User createUser(
            String firstName,
            String lastName,
            String email,
            String phone,
            Role role
    ) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return userRepository.save(User.builder()
                .firstname(firstName)
                .lastname(lastName)
                .email(email)
                .phoneNumber(phone)
                .password(passwordEncoder.encode(PASSWORD))
                .role(role)
                .createdAt(now)
                .emailVerifiedAt(now)
                .build());
    }

    private String login(String email) throws Exception {
        JsonNode response = responseJson(mockMvc.perform(json(
                                post("/api/auth/login"),
                                "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"
                        ))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.token").isNotEmpty()));
        return response.path("token").asText();
    }

    private String createRequest(String payerType, boolean includeOppositeParty, String description) {
        String oppositeParty = includeOppositeParty
                ? """
                ,
                  "oppositeParty": {
                    "firstName": "Rita",
                    "lastName": "Recipient",
                    "email": "%s",
                    "phoneNumber": "%s",
                    "address": {
                      "line1": "22 Receiver Avenue",
                      "city": "Abuja",
                      "state": "FCT",
                      "country": "NG",
                      "postalCode": "900001"
                    }
                  }
                """.formatted(OPPOSITE_EMAIL, OPPOSITE_PHONE)
                : "";
        return """
                {
                  "creatorRole": "SENDER",
                  "payerRole": "%s",
                  "description": "%s",
                  "declaredValue": 25000.00,
                  "creatorAddress": {
                    "line1": "10 Sender Street",
                    "line2": "Suite 2",
                    "city": "Lagos",
                    "state": "Lagos",
                    "country": "NG",
                    "postalCode": "100001"
                  },
                  "parcel": {
                    "weightKg": 2.500,
                    "lengthCm": 30.00,
                    "widthCm": 20.00,
                    "heightCm": 15.00
                  }
                  %s
                }
                """.formatted("CREATOR".equals(payerType) ? "SENDER" : "RECIPIENT", description, oppositeParty);
    }

    private String partyDetailsRequest() {
        return """
                {
                  "firstName": "Completed",
                  "lastName": "Party",
                  "email": "%s",
                  "phoneNumber": "%s",
                  "address": {
                    "line1": "31 Completion Road",
                    "city": "Ibadan",
                    "state": "Oyo",
                    "country": "NG",
                    "postalCode": "200001"
                  }
                }
                """.formatted(PARTY_EMAIL, PARTY_PHONE);
    }

    private String verifyRequest(String challengeId, String code) {
        return "{\"challengeId\":\"" + challengeId + "\",\"code\":\"" + code + "\"}";
    }

    private MockHttpServletRequestBuilder json(
            MockHttpServletRequestBuilder request,
            String body
    ) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletRequestBuilder authenticated(
            MockHttpServletRequestBuilder request,
            String token
    ) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private JsonNode responseJson(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private void assertAuthenticationRequired(MockHttpServletRequestBuilder request) throws Exception {
        int responseStatus = mockMvc.perform(request).andReturn().getResponse().getStatus();
        assertThat(responseStatus).isIn(401, 403);
    }

    private String invitationToken(String invitationLink) {
        return invitationLink.substring(invitationLink.lastIndexOf('/') + 1);
    }

    private String invitationPath(String token) {
        return "/waybills/invitations/" + token;
    }

    private void waitForPickupEmails() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            var notifications = emailNotificationRepository.findAll();
            if (notifications.size() == 2 && notifications.stream().allMatch(notification ->
                    notification.getStatus() == WaybillEmailNotificationStatus.SENT)) {
                return;
            }
            Thread.sleep(20);
        }
    }

    private void cleanDatabase() {
        emailNotificationRepository.deleteAllInBatch();
        refundRepository.deleteAllInBatch();
        receiptRepository.deleteAllInBatch();
        attemptRepository.deleteAllInBatch();
        quoteRepository.deleteAllInBatch();
        shipmentRepository.deleteAllInBatch();
        invitationRepository.deleteAllInBatch();
        parcelRepository.deleteAllInBatch();
        partyRepository.deleteAllInBatch();
        historyRepository.deleteAllInBatch();
        otpChallengeRepository.deleteAllInBatch();
        waybillRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    private record VerifiedInvitation(
            String publicId,
            String invitationToken,
            String accessToken
    ) {
    }
}
