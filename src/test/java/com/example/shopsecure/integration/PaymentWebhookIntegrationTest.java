package com.example.shopsecure.integration;

import com.example.shopsecure.ShopsecureApplication;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.repository.*;
import com.example.shopsecure.service.EmailService;
import com.example.shopsecure.service.PaystackService;
import tools.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = {ShopsecureApplication.class, TestConfig.class, PaymentWebhookIntegrationTest.MockConfig.class})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PaymentWebhookIntegrationTest {

    @TestConfiguration
    static class MockConfig {
        @Bean
        @Primary
        PaystackService paystackService() {
            PaystackService mock = mock(PaystackService.class);
            when(mock.verifyWebhookSignature(anyString(), anyString())).thenReturn(true);
            when(mock.initializeTransaction(anyString(), any(), anyString()))
                    .thenReturn(Map.of("authorization_url", "https://paystack.com/pay/test"));
            return mock;
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
    private OrderRepository orderRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private OrderItemRepository orderItemRepository;
    @Autowired
    private BusinessRepository businessRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private WalletRepository walletRepository;

    private static final String PAYMENT_REF = "PAY-TEST-REF";
    private Order order;
    private Payment payment;
    private Product product;

    @BeforeEach
    void setUp() {
        User vendor = User.builder()
                .firstname("Vendor").lastname("Test")
                .email("vendor@shopsecure.com")
                .password("encoded").role(Role.BUSINESS)
                .createdAt(LocalDateTime.now())
                .build();
        userRepository.save(vendor);

        Business business = Business.builder()
                .businessName("Test Store").owner(vendor)
                .createdAt(LocalDateTime.now())
                .build();
        businessRepository.save(business);

        product = Product.builder()
                .name("Test Product")
                .price(new BigDecimal("100.00")).stock(10)
                .business(business)
                .createdAt(LocalDateTime.now())
                .build();
        productRepository.save(product);

        order = Order.builder()
                .orderNumber("ORD-TEST-001")
                .business(business)
                .customerName("John Customer")
                .email("customer@test.com")
                .phoneNumber("08012345678")
                .deliveryAddress("456 Test Ave")
                .totalAmount(new BigDecimal("700.00"))
                .status(OrderStatus.PENDING)
                .paymentStatus("PENDING")
                .stockDeducted(false)
                .createdAt(LocalDateTime.now())
                .build();
        orderRepository.save(order);

        OrderItem orderItem = OrderItem.builder()
                .order(order).product(product)
                .quantity(2).price(new BigDecimal("100.00"))
                .subtotal(new BigDecimal("200.00"))
                .build();
        orderItemRepository.save(orderItem);

        payment = Payment.builder()
                .reference(PAYMENT_REF)
                .order(order)
                .amount(new BigDecimal("700.00"))
                .status(PaymentStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();
        paymentRepository.save(payment);

        Wallet wallet = Wallet.builder()
                .business(business)
                .balance(BigDecimal.ZERO)
                .totalWithdrawn(BigDecimal.ZERO)
                .currency("NGN")
                .status(WalletStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .build();
        walletRepository.save(wallet);
    }

    @Test
    void initializePayment_success() throws Exception {
        String json = mockMvc.perform(post("/api/payments/initialize")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderNumber\":\"ORD-TEST-001\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(json).contains("authorizationUrl");
        assertThat(json).contains(PAYMENT_REF);
    }

    @Test
    void initializePayment_orderNotFound() throws Exception {
        mockMvc.perform(post("/api/payments/initialize")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderNumber\":\"ORD-NONEXISTENT\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void webhook_chargeSuccess() throws Exception {
        String payload = """
                {"event":"charge.success","data":{"reference":"%s","channel":"card"}}
                """.formatted(PAYMENT_REF);

        mockMvc.perform(post("/api/payments/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-paystack-signature", "test-sig")
                .content(payload))
                .andExpect(status().isOk());

        Payment updatedPayment = paymentRepository.findByReference(PAYMENT_REF).orElseThrow();
        assertThat(updatedPayment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(updatedPayment.getChannel()).isEqualTo("card");

        Order updatedOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(updatedOrder.getPaymentStatus()).isEqualTo("PAID");

        Wallet wallet = walletRepository.findByBusiness_Id(order.getBusiness().getId()).orElseThrow();
        assertThat(wallet.getBalance()).isEqualByComparingTo("700.00");

        Product updatedProduct = productRepository.findById(product.getId()).orElseThrow();
        assertThat(updatedProduct.getStock()).isEqualTo(8);
    }

    @Test
    void webhook_chargeSuccess_idempotent() throws Exception {
        String payload = """
                {"event":"charge.success","data":{"reference":"%s","channel":"card"}}
                """.formatted(PAYMENT_REF);

        mockMvc.perform(post("/api/payments/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-paystack-signature", "test-sig")
                .content(payload))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/payments/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-paystack-signature", "test-sig")
                .content(payload))
                .andExpect(status().isOk());

        Wallet wallet = walletRepository.findByBusiness_Id(order.getBusiness().getId()).orElseThrow();
        assertThat(wallet.getBalance()).isEqualByComparingTo("700.00");
    }
}
