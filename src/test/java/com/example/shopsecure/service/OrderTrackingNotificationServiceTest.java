package com.example.shopsecure.service;

import com.example.shopsecure.entity.*;
import com.example.shopsecure.repository.OrderCounterpartyRepository;
import com.example.shopsecure.repository.OrderShipmentRepository;
import com.example.shopsecure.repository.OrderTrackingNotificationRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OrderTrackingNotificationServiceTest {

    private EmailService emailService;
    private OrderTrackingService trackingService;
    private OrderCounterpartyRepository counterpartyRepository;
    private OrderShipmentRepository shipmentRepository;
    private OrderTrackingNotificationRepository notificationRepository;
    private OrderTrackingNotificationService service;

    @BeforeEach
    void setUp() {
        emailService = mock(EmailService.class);
        trackingService = mock(OrderTrackingService.class);
        counterpartyRepository = mock(OrderCounterpartyRepository.class);
        shipmentRepository = mock(OrderShipmentRepository.class);
        notificationRepository = mock(OrderTrackingNotificationRepository.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new OrderTrackingNotificationService(
                emailService,
                new EmailTemplateService(),
                trackingService,
                counterpartyRepository,
                shipmentRepository,
                notificationRepository,
                transactionManager
        );
        ReflectionTestUtils.setField(service, "maxAttempts", 5);
        ReflectionTestUtils.setField(service, "retryDelay", Duration.ofMinutes(5));
    }

    @Test
    void enqueuesOneTrackingEmailForBuyerAndVendor() {
        Order order = order(12L);
        OrderCounterparty vendor = OrderCounterparty.builder()
                .order(order)
                .firstName("Bola")
                .email("vendor@example.com")
                .build();
        when(counterpartyRepository.findByOrder_Id(order.getId())).thenReturn(Optional.of(vendor));

        service.enqueue(order);

        ArgumentCaptor<OrderTrackingNotification> saved = ArgumentCaptor.forClass(
                OrderTrackingNotification.class
        );
        verify(notificationRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(OrderTrackingNotification::getParticipantRole)
                .containsExactlyInAnyOrder(CreatorRole.BUYER, CreatorRole.VENDOR);
        assertThat(saved.getAllValues())
                .extracting(OrderTrackingNotification::getRecipientEmail)
                .containsExactlyInAnyOrder("buyer@example.com", "vendor@example.com");
        assertThat(saved.getAllValues()).allSatisfy(notification -> {
            assertThat(notification.getStatus()).isEqualTo(OrderTrackingNotificationStatus.PENDING);
            assertThat(notification.getNotificationType())
                    .isEqualTo(OrderTrackingNotificationType.TRACKING_READY);
            assertThat(notification.getNextAttemptAt()).isNotNull();
        });
    }

    @Test
    void pickupNotificationIsQueuedForBothPartiesAndUsesTheDurablePickupTemplate() {
        Order order = order(16L);
        order.setEscrowReleasePolicy(EscrowReleasePolicy.CARRIER_PICKUP);
        order.setSettlementStatus(com.example.shopsecure.entity.SettlementStatus.PENDING);
        order.setCarrierPickupConfirmedAt(Instant.parse("2026-08-20T12:30:00Z"));
        OrderCounterparty vendor = OrderCounterparty.builder()
                .order(order)
                .firstName("Bola")
                .email("vendor@example.com")
                .build();
        OrderShipment shipment = OrderShipment.builder()
                .order(order)
                .status(com.example.shopsecure.provider.shipping.ShipmentStatus.IN_TRANSIT)
                .trackingReference("TRK-PICKUP-16")
                .build();
        when(counterpartyRepository.findByOrder_Id(order.getId())).thenReturn(Optional.of(vendor));

        service.enqueueCarrierPickup(order);

        ArgumentCaptor<OrderTrackingNotification> saved = ArgumentCaptor.forClass(
                OrderTrackingNotification.class
        );
        verify(notificationRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(OrderTrackingNotification::getParticipantRole)
                .containsExactlyInAnyOrder(CreatorRole.BUYER, CreatorRole.VENDOR);
        assertThat(saved.getAllValues()).allSatisfy(notification ->
                assertThat(notification.getNotificationType())
                        .isEqualTo(OrderTrackingNotificationType.CARRIER_PICKUP_CONFIRMED));

        OrderTrackingNotification buyerNotification = saved.getAllValues().stream()
                .filter(notification -> notification.getParticipantRole() == CreatorRole.BUYER)
                .findFirst()
                .orElseThrow();
        buyerNotification.setId(45L);
        buyerNotification.setNextAttemptAt(Instant.now().minusSeconds(1));
        when(notificationRepository.findByIdForUpdate(45L)).thenReturn(Optional.of(buyerNotification));
        when(shipmentRepository.findByOrder_Id(order.getId())).thenReturn(Optional.of(shipment));
        when(trackingService.linkForOrder(order.getId())).thenReturn(
                new OrderTrackingService.TrackingLink(
                        "https://shopsecure.app/orders/tracking/pickup-token",
                        Instant.now()
                )
        );

        service.dispatch(45L);

        assertThat(buyerNotification.getStatus()).isEqualTo(OrderTrackingNotificationStatus.SENT);
        verify(emailService).sendEmail(
                eq("buyer@example.com"),
                eq("Carrier pickup confirmed"),
                argThat(html -> html.contains("TRK-PICKUP-16")
                        && html.contains("queued the item-fund payout")
                        && html.contains("https://shopsecure.app/orders/tracking/pickup-token")),
                eq("order-carrier-pickup/45")
        );
    }

    @Test
    void oneEmailFailureDoesNotBlockTheOtherParticipant() {
        Order order = order(13L);
        OrderTrackingNotification buyer = notification(
                41L,
                order,
                CreatorRole.BUYER,
                "buyer@example.com"
        );
        OrderTrackingNotification vendor = notification(
                42L,
                order,
                CreatorRole.VENDOR,
                "vendor@example.com"
        );
        when(notificationRepository.findByIdForUpdate(buyer.getId())).thenReturn(Optional.of(buyer));
        when(notificationRepository.findByIdForUpdate(vendor.getId())).thenReturn(Optional.of(vendor));
        when(trackingService.linkForOrder(order.getId())).thenReturn(
                new OrderTrackingService.TrackingLink(
                        "https://shopsecure.app/orders/tracking/secure-token",
                        Instant.now()
                )
        );
        doThrow(new IllegalStateException("mail unavailable"))
                .when(emailService).sendEmail(
                        eq("buyer@example.com"),
                        anyString(),
                        anyString(),
                        eq("order-tracking-ready/41")
                );

        service.dispatch(buyer.getId());
        service.dispatch(vendor.getId());

        assertThat(buyer.getStatus()).isEqualTo(OrderTrackingNotificationStatus.PENDING);
        assertThat(buyer.getAttemptCount()).isOne();
        assertThat(buyer.getNextAttemptAt()).isAfter(Instant.now());
        assertThat(vendor.getStatus()).isEqualTo(OrderTrackingNotificationStatus.SENT);
        assertThat(vendor.getSentAt()).isNotNull();
        verify(emailService).sendEmail(
                eq("vendor@example.com"),
                eq("Track your ShopSecure order"),
                argThat(html -> html.contains("https://shopsecure.app/orders/tracking/secure-token")
                        && html.contains("rel=\"noopener noreferrer\"")),
                eq("order-tracking-ready/42")
        );
    }

    @Test
    void linkPreparationFailureConsumesAnAttemptAndDefersTheNotification() {
        Order order = order(14L);
        OrderTrackingNotification notification = notification(
                43L,
                order,
                CreatorRole.BUYER,
                "buyer@example.com"
        );
        when(notificationRepository.findByIdForUpdate(notification.getId()))
                .thenReturn(Optional.of(notification));
        when(trackingService.linkForOrder(order.getId()))
                .thenThrow(new IllegalStateException("cipher unavailable"));

        service.dispatch(notification.getId());

        assertThat(notification.getStatus()).isEqualTo(OrderTrackingNotificationStatus.PENDING);
        assertThat(notification.getAttemptCount()).isOne();
        assertThat(notification.getFirstAttemptAt()).isNotNull();
        assertThat(notification.getNextAttemptAt()).isAfter(Instant.now());
        assertThat(notification.getFailureReason()).isEqualTo("Email preparation failed");
        verifyNoInteractions(emailService);
    }

    @Test
    void repeatedPreparationFailuresDeadLetterAtTheConfiguredLimit() {
        ReflectionTestUtils.setField(service, "maxAttempts", 2);
        Order order = order(15L);
        OrderTrackingNotification notification = notification(
                44L,
                order,
                CreatorRole.VENDOR,
                "vendor@example.com"
        );
        when(notificationRepository.findByIdForUpdate(notification.getId()))
                .thenReturn(Optional.of(notification));
        when(trackingService.linkForOrder(order.getId()))
                .thenThrow(new IllegalStateException("cipher unavailable"));

        service.dispatch(notification.getId());
        notification.setNextAttemptAt(Instant.now().minusSeconds(1));
        service.dispatch(notification.getId());

        assertThat(notification.getStatus()).isEqualTo(OrderTrackingNotificationStatus.FAILED);
        assertThat(notification.getAttemptCount()).isEqualTo(2);
        assertThat(notification.getNextAttemptAt()).isNull();
        assertThat(notification.getFailureReason()).isEqualTo("Email preparation failed");
        verifyNoInteractions(emailService);
    }

    private Order order(Long id) {
        return Order.builder()
                .id(id)
                .creatorRole(CreatorRole.BUYER)
                .creatorFirstName("Ada <Buyer>")
                .creatorEmail("buyer@example.com")
                .itemDescription("Laptop")
                .build();
    }

    private OrderTrackingNotification notification(
            Long id,
            Order order,
            CreatorRole role,
            String email
    ) {
        return OrderTrackingNotification.builder()
                .id(id)
                .order(order)
                .participantRole(role)
                .recipientFirstName(role == CreatorRole.BUYER ? "Ada" : "Bola")
                .recipientEmail(email)
                .status(OrderTrackingNotificationStatus.PENDING)
                .nextAttemptAt(Instant.now().minusSeconds(1))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
