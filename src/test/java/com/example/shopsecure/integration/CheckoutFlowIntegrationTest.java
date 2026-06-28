package com.example.shopsecure.integration;

import com.example.shopsecure.ShopsecureApplication;
import com.example.shopsecure.dto.request.CheckoutRequest;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = {ShopsecureApplication.class, TestConfig.class})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CheckoutFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private CartRepository cartRepository;
    @Autowired
    private CartItemRepository cartItemRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderItemRepository orderItemRepository;
    @Autowired
    private BusinessRepository businessRepository;
    @Autowired
    private ShippingRateRepository shippingRateRepository;
    @Autowired
    private UserRepository userRepository;

    private static final String TEST_SESSION = "test-session-123";
    private Business business;
    private Product product;
    private Cart cart;

    @BeforeEach
    void setUp() {
        User user = User.builder()
                .firstname("Test").lastname("Vendor")
                .email("vendor@shopsecure.com")
                .password("encoded")
                .role(Role.BUSINESS)
                .createdAt(LocalDateTime.now())
                .build();
        userRepository.save(user);

        business = Business.builder()
                .businessName("Test Store").owner(user)
                .createdAt(LocalDateTime.now())
                .build();
        businessRepository.save(business);

        product = Product.builder()
                .name("Test Product")
                .price(new BigDecimal("100.00"))
                .stock(10)
                .business(business)
                .createdAt(LocalDateTime.now())
                .build();
        productRepository.save(product);

        ShippingRate rate = ShippingRate.builder()
                .area("LAGOS MAINLAND")
                .fee(new BigDecimal("500.00"))
                .active(true)
                .build();
        shippingRateRepository.save(rate);

        cart = Cart.builder()
                .sessionId(TEST_SESSION).business(business)
                .totalAmount(new BigDecimal("200.00"))
                .createdAt(LocalDateTime.now())
                .build();
        cartRepository.save(cart);

        CartItem item = CartItem.builder()
                .cart(cart).product(product)
                .quantity(2).price(new BigDecimal("100.00"))
                .subtotal(new BigDecimal("200.00"))
                .build();
        cartItemRepository.save(item);
    }

    @Test
    void checkout_success() throws Exception {
        CheckoutRequest req = new CheckoutRequest();
        req.setSessionId(TEST_SESSION);
        req.setBusinessId(business.getId());
        req.setCustomerName("John Doe");
        req.setEmail("john@test.com");
        req.setPhoneNumber("08012345678");
        req.setDeliveryAddress("123 Test St");
        req.setShippingArea("LAGOS MAINLAND");

        mockMvc.perform(post("/api/checkout")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").isString())
                .andExpect(jsonPath("$.customerName").value("John Doe"))
                .andExpect(jsonPath("$.email").value("john@test.com"))
                .andExpect(jsonPath("$.totalAmount").value(700.00))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productName").value("Test Product"))
                .andExpect(jsonPath("$.items[0].quantity").value(2));

        Product updated = productRepository.findById(product.getId()).orElseThrow();
        assertThat(updated.getStock()).isEqualTo(8);

        assertThat(cartItemRepository.findByCart_Id(cart.getId())).isEmpty();
    }

    @Test
    void checkout_insufficientStock() throws Exception {
        product.setStock(1);
        productRepository.save(product);

        CheckoutRequest req = new CheckoutRequest();
        req.setSessionId(TEST_SESSION);
        req.setBusinessId(business.getId());
        req.setCustomerName("John Doe");
        req.setEmail("john@test.com");
        req.setPhoneNumber("08012345678");
        req.setDeliveryAddress("123 Test St");
        req.setShippingArea("LAGOS MAINLAND");

        mockMvc.perform(post("/api/checkout")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());

        Product updated = productRepository.findById(product.getId()).orElseThrow();
        assertThat(updated.getStock()).isEqualTo(1);

        assertThat(cartItemRepository.findByCart_Id(cart.getId())).hasSize(1);
    }
}
