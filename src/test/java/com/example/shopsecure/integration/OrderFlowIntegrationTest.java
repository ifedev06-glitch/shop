package com.example.shopsecure.integration;

import com.example.shopsecure.ShopsecureApplication;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.repository.*;
import com.example.shopsecure.service.EmailService;
import com.example.shopsecure.service.WhatsAppBotService;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = {
        ShopsecureApplication.class,
        TestConfig.class,
        OrderFlowIntegrationTest.MockConfig.class
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderFlowIntegrationTest {

    @TestConfiguration
    static class MockConfig {
        @Bean
        @Primary
        WhatsAppBotService whatsAppBotService() {
            return mock(WhatsAppBotService.class);
        }

        @Bean
        @Primary
        EmailService emailService() {
            return mock(EmailService.class);
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderCounterpartyRepository counterpartyRepository;
    @Autowired
    private OtpChallengeRepository otpChallengeRepository;
    @Autowired
    private OrderStatusHistoryRepository historyRepository;
    @Autowired
    private WalletRepository walletRepository;
    @Autowired
    private WalletTransactionRepository walletTransactionRepository;
    @Autowired
    private WhatsAppBotService whatsAppBotService;
    @Autowired
    private EmailService emailService;

    private final AtomicReference<String> deliveredOtp = new AtomicReference<>();
    private User creator;
    private Wallet wallet;

    @BeforeEach
    void setUp() {
        reset(whatsAppBotService, emailService);
        deliveredOtp.set(null);
        doAnswer(invocation -> {
            deliveredOtp.set(invocation.getArgument(1));
            return null;
        }).when(whatsAppBotService).sendOtp(anyString(), anyString());

        creator = userRepository.save(User.builder()
                .firstname("Ada")
                .lastname("Buyer")
                .email("account@example.com")
                .password("encoded")
                .role(Role.USER)
                .createdAt(LocalDateTime.now())
                .build());
        wallet = walletRepository.save(Wallet.builder()
                .user(creator)
                .availableBalance(new BigDecimal("150000.00"))
                .processingBalance(BigDecimal.ZERO.setScale(2))
                .currency("NGN")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
    }

    @AfterEach
    void cleanUp() {
        walletTransactionRepository.deleteAll();
        historyRepository.deleteAll();
        counterpartyRepository.deleteAll();
        otpChallengeRepository.deleteAll();
        orderRepository.deleteAll();
        walletRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @WithMockUser(username = "account@example.com", roles = "USER")
    void completesRequestedOrderCreationAndWalletEscrowFlow() throws Exception {
        JsonNode created = createOrder();
        long orderId = created.get("id").asLong();
        String shareToken = created.get("shareToken").asText();

        Order newOrder = orderRepository.findById(orderId).orElseThrow();
        assertThat(newOrder.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(newOrder.getBuyerPhoneNumber()).isEqualTo("2348098765432");
        assertThat(newOrder.getBuyerEmail()).isEqualTo("buyer-contact@example.com");
        assertThat(newOrder.getDeliveryAddressLine1()).isEqualTo("20 Admiralty Way");

        mockMvc.perform(get("/orders/token/{shareToken}", shareToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(125000.00))
                .andExpect(jsonPath("$.itemDescription").value("MacBook Air M3"))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.buyerEmail").doesNotExist())
                .andExpect(jsonPath("$.buyerPhoneNumber").doesNotExist())
                .andExpect(jsonPath("$.shareToken").doesNotExist());

        submitAndVerifyVendor(shareToken);

        mockMvc.perform(post("/orders/{orderId}/pay", orderId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("A valid Idempotency-Key header is required"));

        String paymentResponse = mockMvc.perform(post("/orders/{orderId}/pay", orderId)
                        .header("Idempotency-Key", "wallet-pay-order-" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID_ESCROW"))
                .andExpect(jsonPath("$.amount").value(125000.00))
                .andExpect(jsonPath("$.availableBalance").value(25000.00))
                .andExpect(jsonPath("$.processingBalance").value(125000.00))
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(post("/orders/{orderId}/pay", orderId)
                        .header("Idempotency-Key", "wallet-pay-order-" + orderId))
                .andExpect(status().isOk())
                .andExpect(content().json(paymentResponse));
        mockMvc.perform(post("/orders/{orderId}/pay", orderId)
                        .header("Idempotency-Key", "different-retry-key"))
                .andExpect(status().isOk())
                .andExpect(content().json(paymentResponse));

        Wallet paidWallet = walletRepository.findById(wallet.getId()).orElseThrow();
        assertThat(paidWallet.getAvailableBalance()).isEqualByComparingTo("25000.00");
        assertThat(paidWallet.getProcessingBalance()).isEqualByComparingTo("125000.00");
        assertThat(walletTransactionRepository.count()).isEqualTo(1);

        WalletTransaction ledgerEntry = walletTransactionRepository.findByOrder_Id(orderId).orElseThrow();
        assertThat(ledgerEntry.getIdempotencyKey()).isEqualTo("wallet-pay-order-" + orderId);
        assertThat(ledgerEntry.getAmount()).isEqualByComparingTo("125000.00");

        Order paidOrder = orderRepository.findById(orderId).orElseThrow();
        assertThat(paidOrder.getStatus()).isEqualTo(OrderStatus.PAID_ESCROW);
        assertThat(paidOrder.getPaidAt()).isNotNull();

        mockMvc.perform(get("/orders/{orderId}/history", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].toStatus").value("CREATED"))
                .andExpect(jsonPath("$[1].toStatus").value("VENDOR_INFO_COMPLETE"))
                .andExpect(jsonPath("$[2].toStatus").value("PAID_ESCROW"));
    }

    @Test
    @WithMockUser(username = "account@example.com", roles = "USER")
    void rejectsPaymentWhenAvailableBalanceIsInsufficient() throws Exception {
        JsonNode created = createOrder();
        long orderId = created.get("id").asLong();
        submitAndVerifyVendor(created.get("shareToken").asText());

        wallet.setAvailableBalance(new BigDecimal("100.00"));
        walletRepository.saveAndFlush(wallet);

        mockMvc.perform(post("/orders/{orderId}/pay", orderId)
                        .header("Idempotency-Key", "insufficient-order-" + orderId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "Insufficient wallet balance. Top up your wallet before paying for this order."
                ));

        Wallet unchanged = walletRepository.findById(wallet.getId()).orElseThrow();
        assertThat(unchanged.getAvailableBalance()).isEqualByComparingTo("100.00");
        assertThat(unchanged.getProcessingBalance()).isZero();
        assertThat(walletTransactionRepository.count()).isZero();
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.VENDOR_INFO_COMPLETE);
    }

    @Test
    void createOrderRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validOrderJson()))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
    }

    @Test
    void registrationCreatesZeroBalanceWallet() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstname":"New",
                                  "lastname":"Buyer",
                                  "email":"new-buyer@example.com",
                                  "password":"Secure1!"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("new-buyer@example.com"));

        User registered = userRepository.findByEmail("new-buyer@example.com").orElseThrow();
        Wallet registeredWallet = walletRepository.findByUser_Id(registered.getId()).orElseThrow();
        assertThat(registeredWallet.getAvailableBalance()).isZero();
        assertThat(registeredWallet.getProcessingBalance()).isZero();
        assertThat(registeredWallet.getCurrency()).isEqualTo("NGN");
    }

    @Test
    @WithMockUser(username = "account@example.com", roles = "USER")
    void createOrderValidatesBuyerContactAndAddress() throws Exception {
        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":-1,\"itemDescription\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.amount").exists())
                .andExpect(jsonPath("$.details.itemDescription").exists())
                .andExpect(jsonPath("$.details.buyerEmail").exists())
                .andExpect(jsonPath("$.details.buyerPhoneNumber").exists())
                .andExpect(jsonPath("$.details.deliveryAddress").exists());
    }

    @Test
    @WithMockUser(username = "intruder@example.com", roles = "USER")
    void anotherUserCannotPayForBuyerOrder() throws Exception {
        userRepository.save(User.builder()
                .firstname("Eve")
                .lastname("Intruder")
                .email("intruder@example.com")
                .password("encoded")
                .role(Role.USER)
                .createdAt(LocalDateTime.now())
                .build());
        Order order = persistedAcceptedOrder(creator);

        mockMvc.perform(post("/orders/{orderId}/pay", order.getId())
                        .header("Idempotency-Key", "intruder-payment"))
                .andExpect(status().isForbidden());

        assertThat(walletRepository.findById(wallet.getId()).orElseThrow().getAvailableBalance())
                .isEqualByComparingTo("150000.00");
        assertThat(walletTransactionRepository.count()).isZero();
    }

    private JsonNode createOrder() throws Exception {
        String json = mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validOrderJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.creatorRole").value("BUYER"))
                .andExpect(jsonPath("$.shareToken").isNotEmpty())
                .andExpect(jsonPath("$.shareableLink").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    private void submitAndVerifyVendor(String shareToken) throws Exception {
        mockMvc.perform(post("/orders/token/{shareToken}/vendor-info", shareToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "phoneNumber":"2348012345678",
                                  "email":"vendor@example.com",
                                  "address":{
                                    "line1":"12 Marina Road",
                                    "city":"Lagos",
                                    "state":"Lagos",
                                    "country":"Nigeria"
                                  },
                                  "bankAccount":{
                                    "accountNumber":"0123456789",
                                    "bankName":"Guarantee Trust Bank"
                                  }
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.phoneVerified").value(false));

        Order order = orderRepository.findByShareToken(shareToken).orElseThrow();
        OrderCounterparty pending = counterpartyRepository.findByOrder_Id(order.getId()).orElseThrow();
        assertThat(pending.getBankAccountNumber()).isEqualTo("0123456789");
        assertThat(pending.getBankName()).isEqualTo("Guarantee Trust Bank");
        assertThat(pending.isPhoneVerified()).isFalse();

        assertThat(deliveredOtp.get()).matches("\\d{6}");
        String wrongCode = "000000".equals(deliveredOtp.get()) ? "111111" : "000000";
        mockMvc.perform(post("/orders/token/{shareToken}/verify-otp", shareToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + wrongCode + "\"}"))
                .andExpect(status().isBadRequest());
        assertThat(otpChallengeRepository
                .findTopByPurposeAndSubjectOrderByCreatedAtDesc(OtpPurpose.ORDER_COUNTERPARTY, shareToken)
                .orElseThrow()
                .getAttempts()).isEqualTo(1);

        mockMvc.perform(post("/orders/token/{shareToken}/verify-otp", shareToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + deliveredOtp.get() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VENDOR_INFO_COMPLETE"))
                .andExpect(jsonPath("$.phoneVerified").value(true));

        verify(emailService, timeout(1000)).sendEmail(
                eq("buyer-contact@example.com"),
                eq("Vendor accepted - order ready for payment"),
                contains("MacBook Air M3")
        );
    }

    private String validOrderJson() {
        return """
                {
                  "amount":125000.00,
                  "itemDescription":"MacBook Air M3",
                  "buyerPhoneNumber":"2348098765432",
                  "buyerEmail":"buyer-contact@example.com",
                  "deliveryAddress":{
                    "line1":"20 Admiralty Way",
                    "city":"Lagos",
                    "state":"Lagos",
                    "country":"Nigeria"
                  }
                }
                """;
    }

    private Order persistedAcceptedOrder(User user) {
        return orderRepository.save(Order.builder()
                .amount(new BigDecimal("500.00"))
                .itemDescription("Camera")
                .buyerPhoneNumber("2348098765432")
                .buyerEmail("buyer-contact@example.com")
                .deliveryAddressLine1("20 Admiralty Way")
                .deliveryCity("Lagos")
                .deliveryState("Lagos")
                .deliveryCountry("Nigeria")
                .creatorRole(CreatorRole.BUYER)
                .status(OrderStatus.VENDOR_INFO_COMPLETE)
                .shareToken("public-token-for-safe-order-view-1234567890123")
                .creator(user)
                .createdAt(LocalDateTime.now())
                .updatedAt(Instant.now())
                .build());
    }
}
