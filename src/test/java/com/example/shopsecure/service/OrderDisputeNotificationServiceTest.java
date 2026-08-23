package com.example.shopsecure.service;

import com.example.shopsecure.entity.*;
import com.example.shopsecure.repository.OrderDisputeNotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderDisputeNotificationServiceTest {

    private EmailService emailService;
    private OrderDisputeNotificationRepository notificationRepository;
    private OrderDisputeNotificationService service;

    @BeforeEach
    void setUp() {
        emailService = mock(EmailService.class);
        notificationRepository = mock(OrderDisputeNotificationRepository.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new OrderDisputeNotificationService(
                emailService,
                new EmailTemplateService(),
                notificationRepository,
                transactionManager
        );
        ReflectionTestUtils.setField(service, "maxAttempts", 5);
        ReflectionTestUtils.setField(service, "retryDelay", Duration.ofMinutes(5));
    }

    @Test
    void enqueuesBuyerVendorAndOperationsIndependently() {
        Order order = Order.builder()
                .id(42L)
                .creatorRole(CreatorRole.VENDOR)
                .creatorFirstName("Vendor")
                .creatorEmail("vendor@example.com")
                .build();
        OrderDispute dispute = OrderDispute.builder().id(9L).order(order).build();
        OrderCounterparty buyer = OrderCounterparty.builder()
                .order(order)
                .firstName("Buyer")
                .email("buyer@example.com")
                .build();

        service.enqueueOpened(dispute, buyer);

        ArgumentCaptor<OrderDisputeNotification> saved = ArgumentCaptor.forClass(
                OrderDisputeNotification.class
        );
        verify(notificationRepository, times(3)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(OrderDisputeNotification::getAudience)
                .containsExactlyInAnyOrder(
                        OrderDisputeNotificationAudience.BUYER,
                        OrderDisputeNotificationAudience.VENDOR,
                        OrderDisputeNotificationAudience.OPERATIONS
                );
        assertThat(saved.getAllValues())
                .extracting(OrderDisputeNotification::getRecipientEmail)
                .containsExactlyInAnyOrder(
                        "buyer@example.com",
                        "vendor@example.com",
                        "fizzois580@gmail.com"
                );
        assertThat(saved.getAllValues()).allSatisfy(notification -> {
            assertThat(notification.getStatus()).isEqualTo(OrderDisputeNotificationStatus.PENDING);
            assertThat(notification.getNextAttemptAt()).isNotNull();
        });
    }

    @Test
    void dispatchEscapesReasonAndUsesStableIdempotencyKey() {
        OrderDisputeNotification notification = notification(
                31L,
                OrderDisputeNotificationAudience.BUYER,
                "buyer@example.com"
        );
        when(notificationRepository.findByIdForUpdate(31L)).thenReturn(Optional.of(notification));

        service.dispatch(31L);

        assertThat(notification.getStatus()).isEqualTo(OrderDisputeNotificationStatus.SENT);
        verify(emailService).sendEmail(
                eq("buyer@example.com"),
                eq("ShopSecure order dispute received #42"),
                argThat(html -> html.contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;")
                        && html.contains("team will review the dispute")
                        && !html.contains("<script>")),
                eq("order-dispute-opened/31")
        );
    }

    @Test
    void oneRecipientFailureDoesNotBlockAnotherRecipient() {
        OrderDisputeNotification buyer = notification(
                32L,
                OrderDisputeNotificationAudience.BUYER,
                "buyer@example.com"
        );
        OrderDisputeNotification operations = notification(
                33L,
                OrderDisputeNotificationAudience.OPERATIONS,
                "fizzois580@gmail.com"
        );
        when(notificationRepository.findByIdForUpdate(32L)).thenReturn(Optional.of(buyer));
        when(notificationRepository.findByIdForUpdate(33L)).thenReturn(Optional.of(operations));
        doThrow(new IllegalStateException("mail unavailable"))
                .when(emailService).sendEmail(
                        eq("buyer@example.com"), anyString(), anyString(), eq("order-dispute-opened/32")
                );

        service.dispatch(32L);
        service.dispatch(33L);

        assertThat(buyer.getStatus()).isEqualTo(OrderDisputeNotificationStatus.PENDING);
        assertThat(buyer.getAttemptCount()).isOne();
        assertThat(operations.getStatus()).isEqualTo(OrderDisputeNotificationStatus.SENT);
        verify(emailService).sendEmail(
                eq("fizzois580@gmail.com"),
                eq("New ShopSecure order dispute #42"),
                anyString(),
                eq("order-dispute-opened/33")
        );
    }

    private OrderDisputeNotification notification(
            Long id,
            OrderDisputeNotificationAudience audience,
            String recipientEmail
    ) {
        Order order = Order.builder()
                .id(42L)
                .itemDescription("Laptop")
                .build();
        OrderDispute dispute = OrderDispute.builder()
                .id(9L)
                .order(order)
                .reason("<script>alert('x')</script>")
                .build();
        return OrderDisputeNotification.builder()
                .id(id)
                .dispute(dispute)
                .audience(audience)
                .recipientFirstName(audience == OrderDisputeNotificationAudience.OPERATIONS
                        ? "Operations"
                        : "Ada")
                .recipientEmail(recipientEmail)
                .status(OrderDisputeNotificationStatus.PENDING)
                .nextAttemptAt(Instant.now().minusSeconds(1))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
