package com.example.shopsecure.integration;

import com.example.shopsecure.ShopsecureApplication;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.repository.OrderCounterpartyRepository;
import com.example.shopsecure.repository.OrderDisputeNotificationRepository;
import com.example.shopsecure.repository.OrderDisputeRepository;
import com.example.shopsecure.repository.OrderRepository;
import com.example.shopsecure.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = {ShopsecureApplication.class, TestConfig.class},
        properties = {
                "spring.datasource.url=jdbc:h2:mem:orderdispute;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
                "rate-limit.enabled=false"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OrderDisputeIntegrationTest {

    private static final String ACCEPTED_MESSAGE =
            "If the order is eligible, the dispute has been received and the ShopSecure team will contact both parties.";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderCounterpartyRepository counterpartyRepository;
    @Autowired
    private OrderDisputeRepository disputeRepository;
    @Autowired
    private OrderDisputeNotificationRepository notificationRepository;

    private Order order;

    @BeforeEach
    void setUp() {
        LocalDateTime now = LocalDateTime.now();
        Instant instant = Instant.now();
        User buyer = userRepository.save(User.builder()
                .firstname("Ada")
                .lastname("Buyer")
                .email("buyer.dispute@example.com")
                .phoneNumber("+2348012345678")
                .password("encoded-password")
                .role(Role.USER)
                .createdAt(now)
                .emailVerifiedAt(now)
                .build());
        order = orderRepository.save(Order.builder()
                .itemAmount(new BigDecimal("100000.00"))
                .currency("NGN")
                .itemDescription("Laptop <sealed>")
                .creatorFirstName("Ada")
                .creatorLastName("Buyer")
                .creatorPhoneNumber("+2348012345678")
                .creatorEmail("buyer.dispute@example.com")
                .creatorAddressLine1("1 Buyer Street")
                .creatorAddressLine2("")
                .creatorCity("Lagos")
                .creatorState("Lagos")
                .creatorCountry("NG")
                .creatorPostalCode("100001")
                .creatorRole(CreatorRole.BUYER)
                .fundingMode(OrderFundingMode.WALLET)
                .status(OrderStatus.IN_TRANSIT)
                .shareToken("dispute-share-" + UUID.randomUUID())
                .creator(buyer)
                .paymentStatus(PaymentStatus.CONFIRMED)
                .settlementStatus(SettlementStatus.PENDING)
                .fundedAt(instant.minusSeconds(3600))
                .createdAt(now.minusDays(1))
                .updatedAt(instant)
                .build());
        counterpartyRepository.save(OrderCounterparty.builder()
                .order(order)
                .firstName("Bola")
                .lastName("Vendor")
                .phoneNumber("+2348098765432")
                .email("vendor.dispute@example.com")
                .addressLine1("2 Vendor Road")
                .addressLine2("")
                .city("Abuja")
                .state("FCT")
                .country("NG")
                .postalCode("900001")
                .phoneVerified(true)
                .createdAt(now.minusDays(1))
                .updatedAt(instant)
                .build());
    }

    @Test
    void anonymousSubmissionPersistsOnceQueuesThreeEmailsAndLeavesOrderStateUnchanged() throws Exception {
        String firstResponse = mockMvc.perform(post("/orders/{orderId}/disputes", order.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"  Package arrived damaged <script>alert('x')</script>.  "}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.message").value(ACCEPTED_MESSAGE))
                .andReturn()
                .getResponse()
                .getContentAsString();

        OrderDispute dispute = disputeRepository.findByOrder_Id(order.getId()).orElseThrow();
        assertThat(dispute.getReason()).isEqualTo("Package arrived damaged <script>alert('x')</script>.");
        assertThat(dispute.getStatus()).isEqualTo(OrderDisputeStatus.OPEN);
        assertThat(notificationRepository.findByDispute_IdOrderByAudienceAsc(dispute.getId()))
                .extracting(
                        OrderDisputeNotification::getAudience,
                        OrderDisputeNotification::getRecipientEmail
                )
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                OrderDisputeNotificationAudience.BUYER,
                                "buyer.dispute@example.com"
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                OrderDisputeNotificationAudience.VENDOR,
                                "vendor.dispute@example.com"
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                OrderDisputeNotificationAudience.OPERATIONS,
                                "fizzois580@gmail.com"
                        )
                );
        assertThat(order.getStatus()).isEqualTo(OrderStatus.IN_TRANSIT);
        assertThat(order.getSettlementStatus()).isEqualTo(SettlementStatus.PENDING);

        String duplicateResponse = mockMvc.perform(post("/orders/{orderId}/disputes", order.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"A different duplicate reason\"}"))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String unknownResponse = mockMvc.perform(post("/orders/{orderId}/disputes", 999999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Unknown order reason\"}"))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(duplicateResponse).isEqualTo(firstResponse);
        assertThat(unknownResponse).isEqualTo(firstResponse);
        assertThat(disputeRepository.count()).isOne();
        assertThat(notificationRepository.count()).isEqualTo(3);
    }

    @Test
    void validatesReasonAndExposesReadOnlyAdminListDetailAndDashboardCount() throws Exception {
        mockMvc.perform(post("/orders/{orderId}/disputes", order.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/orders/{orderId}/disputes", order.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"\\u0000\"}"))
                .andExpect(status().isBadRequest());

        int nonCanonicalStatus = mockMvc.perform(post("/orders/0" + order.getId() + "/disputes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Non-canonical order ID\"}"))
                .andReturn()
                .getResponse()
                .getStatus();
        assertThat(nonCanonicalStatus).isIn(401, 403);

        mockMvc.perform(post("/orders/{orderId}/disputes", order.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Delivery box was visibly damaged\"}"))
                .andExpect(status().isAccepted());
        Long disputeId = disputeRepository.findByOrder_Id(order.getId()).orElseThrow().getId();

        mockMvc.perform(get("/api/admin/disputes")
                        .with(user("admin@example.com").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(disputeId))
                .andExpect(jsonPath("$.content[0].orderId").value(order.getId()))
                .andExpect(jsonPath("$.content[0].status").value("OPEN"))
                .andExpect(jsonPath("$.content[0].reasonPreview")
                        .value("Delivery box was visibly damaged"));

        mockMvc.perform(get("/api/admin/disputes/{disputeId}", disputeId)
                        .with(user("admin@example.com").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("Delivery box was visibly damaged"))
                .andExpect(jsonPath("$.buyer.maskedEmail").value("b***@example.com"))
                .andExpect(jsonPath("$.vendor.maskedEmail").value("v***@example.com"))
                .andExpect(jsonPath("$.notifications.length()").value(3));

        mockMvc.perform(get("/api/admin/dashboard")
                        .with(user("admin@example.com").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operations.disputesByStatus.OPEN").value(1))
                .andExpect(jsonPath("$.operations.openDisputes").value(1));
    }
}
