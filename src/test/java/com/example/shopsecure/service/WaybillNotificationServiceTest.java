package com.example.shopsecure.service;

import com.example.shopsecure.entity.Waybill;
import com.example.shopsecure.entity.WaybillEmailNotification;
import com.example.shopsecure.entity.WaybillEmailNotificationStatus;
import com.example.shopsecure.entity.WaybillEmailNotificationType;
import com.example.shopsecure.entity.WaybillParty;
import com.example.shopsecure.entity.WaybillPartyRole;
import com.example.shopsecure.entity.WaybillShipment;
import com.example.shopsecure.entity.WaybillStatus;
import com.example.shopsecure.provider.shipping.ShipmentStatus;
import com.example.shopsecure.provider.shipping.ShipmentTrackingStatus;
import com.example.shopsecure.provider.shipping.ShippingProvider;
import com.example.shopsecure.repository.WaybillEmailNotificationRepository;
import com.example.shopsecure.repository.WaybillPartyRepository;
import com.example.shopsecure.repository.WaybillShipmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WaybillNotificationServiceTest {

    private EmailService emailService;
    private WaybillPartyRepository partyRepository;
    private WaybillShipmentRepository shipmentRepository;
    private WaybillEmailNotificationRepository notificationRepository;
    private ShippingProvider shippingProvider;
    private ShippingProviderRoutingService shippingRoutingService;
    private PlatformTransactionManager transactionManager;
    private WaybillNotificationService service;

    @BeforeEach
    void setUp() {
        emailService = mock(EmailService.class);
        partyRepository = mock(WaybillPartyRepository.class);
        shipmentRepository = mock(WaybillShipmentRepository.class);
        notificationRepository = mock(WaybillEmailNotificationRepository.class);
        shippingProvider = mock(ShippingProvider.class);
        shippingRoutingService = mock(ShippingProviderRoutingService.class);
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new WaybillNotificationService(
                emailService,
                new EmailTemplateService(),
                partyRepository,
                shipmentRepository,
                notificationRepository,
                shippingRoutingService,
                transactionManager
        );
        when(shippingRoutingService.require(anyString())).thenReturn(shippingProvider);
        ReflectionTestUtils.setField(service, "maxAttempts", 5);
        ReflectionTestUtils.setField(service, "retryDelay", Duration.ofMinutes(5));
    }

    @Test
    void enqueuesOneSafeTrackingEmailPerParty() {
        Waybill waybill = waybill(12L, "Documents <script>alert('x')</script>");
        WaybillShipment shipment = shipment(
                waybill,
                "TRACK-12",
                "https://tracking.terminal.test/TRACK-12"
        );
        WaybillParty sender = party(21L, "Ada <Sender>", "sender@example.com", WaybillPartyRole.SENDER);
        WaybillParty recipient = party(22L, "Tobi", "recipient@example.com", WaybillPartyRole.RECIPIENT);
        when(partyRepository.findByWaybill_IdOrderByRoleAsc(waybill.getId()))
                .thenReturn(List.of(recipient, sender));

        service.enqueueAwaitingPickup(waybill, shipment);

        ArgumentCaptor<WaybillEmailNotification> notification = ArgumentCaptor.forClass(
                WaybillEmailNotification.class
        );
        verify(notificationRepository, org.mockito.Mockito.times(2)).save(notification.capture());
        assertThat(notification.getAllValues()).allSatisfy(saved -> {
            assertThat(saved.getStatus()).isEqualTo(WaybillEmailNotificationStatus.PENDING);
            assertThat(saved.getNotificationType()).isEqualTo(
                    WaybillEmailNotificationType.AWAITING_PICKUP
            );
            assertThat(saved.getHtmlBody())
                    .contains("https://tracking.terminal.test/TRACK-12")
                    .contains("TRACK-12")
                    .contains("rel=\"noopener noreferrer\"")
                    .doesNotContain("<script>");
        });
        assertThat(notification.getAllValues())
                .extracting(WaybillEmailNotification::getRecipientEmail)
                .containsExactlyInAnyOrder("sender@example.com", "recipient@example.com");
    }

    @Test
    void dispatchRetriesFailureWithoutBlockingAnotherNotification() {
        Waybill waybill = waybill(13L, "Documents");
        WaybillParty sender = party(31L, "Ada", "sender@example.com", WaybillPartyRole.SENDER);
        WaybillParty recipient = party(32L, "Tobi", "recipient@example.com", WaybillPartyRole.RECIPIENT);
        WaybillShipment shipment = shipment(
                waybill,
                "TRACK-13",
                "https://tracking.terminal.test/TRACK-13"
        );
        WaybillEmailNotification failed = notification(41L, waybill, sender, "sender body");
        WaybillEmailNotification successful = notification(42L, waybill, recipient, "recipient body");
        when(shipmentRepository.findByWaybill_Id(waybill.getId())).thenReturn(Optional.of(shipment));
        when(notificationRepository.findByIdForUpdate(failed.getId())).thenReturn(Optional.of(failed));
        when(notificationRepository.findByIdForUpdate(successful.getId()))
                .thenReturn(Optional.of(successful));
        doThrow(new IllegalStateException("mail unavailable"))
                .when(emailService).sendEmail(
                        eq(sender.getEmail()),
                        anyString(),
                        anyString(),
                        eq("waybill-awaiting-pickup/41")
                );

        service.dispatch(failed.getId());
        service.dispatch(successful.getId());

        assertThat(failed.getStatus()).isEqualTo(WaybillEmailNotificationStatus.PENDING);
        assertThat(failed.getAttemptCount()).isOne();
        assertThat(failed.getNextAttemptAt()).isAfter(LocalDateTime.now(ZoneOffset.UTC));
        assertThat(successful.getStatus()).isEqualTo(WaybillEmailNotificationStatus.SENT);
        assertThat(successful.getSentAt()).isNotNull();
        verify(emailService).sendEmail(
                eq(recipient.getEmail()),
                anyString(),
                anyString(),
                eq("waybill-awaiting-pickup/42")
        );
    }

    @Test
    void missingTrackingLinkIsRefreshedFromProviderBeforeSending() {
        Waybill waybill = waybill(14L, "Documents");
        WaybillParty sender = party(51L, "Ada", "sender@example.com", WaybillPartyRole.SENDER);
        WaybillEmailNotification notification = notification(61L, waybill, sender, null);
        WaybillShipment shipment = shipment(waybill, "TRACK-14", null);
        when(notificationRepository.findByIdForUpdate(notification.getId()))
                .thenReturn(Optional.of(notification));
        when(shipmentRepository.findByWaybill_Id(waybill.getId())).thenReturn(Optional.of(shipment));
        when(shipmentRepository.findByWaybillIdForUpdate(waybill.getId()))
                .thenReturn(Optional.of(shipment));
        when(shippingProvider.trackShipment(shipment.getProviderShipmentId()))
                .thenReturn(new ShipmentTrackingStatus(
                        shipment.getProviderShipmentId(),
                        ShipmentStatus.CONFIRMED,
                        Instant.now(),
                        List.of(),
                        "TRACK-14-REFRESHED",
                        "https://tracking.terminal.test/TRACK-14"
                ));

        service.dispatch(notification.getId());

        assertThat(notification.getStatus()).isEqualTo(WaybillEmailNotificationStatus.SENT);
        assertThat(notification.getHtmlBody()).contains("https://tracking.terminal.test/TRACK-14");
        assertThat(shipment.getTrackingReference()).isEqualTo("TRACK-14-REFRESHED");
        assertThat(shipment.getTrackingUrl()).isEqualTo(
                "https://tracking.terminal.test/TRACK-14"
        );
        verify(emailService).sendEmail(
                eq(sender.getEmail()),
                anyString(),
                anyString(),
                eq("waybill-awaiting-pickup/61")
        );
    }

    @Test
    void unavailableTrackingLinkRemainsPendingWithoutSending() {
        Waybill waybill = waybill(15L, "Documents");
        WaybillParty sender = party(52L, "Ada", "sender@example.com", WaybillPartyRole.SENDER);
        WaybillEmailNotification notification = notification(62L, waybill, sender, null);
        WaybillShipment shipment = shipment(waybill, "TRACK-15", null);
        when(notificationRepository.findByIdForUpdate(notification.getId()))
                .thenReturn(Optional.of(notification));
        when(shipmentRepository.findByWaybill_Id(waybill.getId())).thenReturn(Optional.of(shipment));
        when(shipmentRepository.findByWaybillIdForUpdate(waybill.getId()))
                .thenReturn(Optional.of(shipment));
        when(shippingProvider.trackShipment(shipment.getProviderShipmentId()))
                .thenReturn(new ShipmentTrackingStatus(
                        shipment.getProviderShipmentId(),
                        ShipmentStatus.CONFIRMED,
                        Instant.now(),
                        List.of(),
                        "TRACK-15",
                        null
                ));

        service.dispatch(notification.getId());

        assertThat(notification.getStatus()).isEqualTo(WaybillEmailNotificationStatus.PENDING);
        assertThat(notification.getAttemptCount()).isZero();
        assertThat(notification.getFailureReason()).isEqualTo("Tracking URL is not available yet");
        assertThat(notification.getDispatchToken()).isNull();
        verify(emailService, never()).sendEmail(
                anyString(),
                anyString(),
                anyString(),
                anyString()
        );
    }

    @Test
    void obsoleteAwaitingPickupMessageIsSkipped() {
        Waybill waybill = waybill(16L, "Documents");
        waybill.setStatus(WaybillStatus.COMPLETED);
        WaybillParty sender = party(53L, "Ada", "sender@example.com", WaybillPartyRole.SENDER);
        WaybillEmailNotification notification = notification(63L, waybill, sender, "body");
        when(notificationRepository.findByIdForUpdate(notification.getId()))
                .thenReturn(Optional.of(notification));

        service.dispatch(notification.getId());

        assertThat(notification.getStatus()).isEqualTo(WaybillEmailNotificationStatus.SKIPPED);
        assertThat(notification.getFailureReason()).contains("no longer");
        verify(emailService, never()).sendEmail(
                anyString(),
                anyString(),
                anyString(),
                anyString()
        );
    }

    @Test
    void recoverableShippingFailureDefersInsteadOfSkipping() {
        Waybill waybill = waybill(17L, "Documents");
        waybill.setStatus(WaybillStatus.SHIPPING_FAILED);
        WaybillParty sender = party(54L, "Ada", "sender@example.com", WaybillPartyRole.SENDER);
        WaybillEmailNotification notification = notification(64L, waybill, sender, "body");
        when(notificationRepository.findByIdForUpdate(notification.getId()))
                .thenReturn(Optional.of(notification));

        service.dispatch(notification.getId());

        assertThat(notification.getStatus()).isEqualTo(WaybillEmailNotificationStatus.PENDING);
        assertThat(notification.getAttemptCount()).isZero();
        assertThat(notification.getFailureReason()).contains("return to the pickup stage");
        verify(emailService, never()).sendEmail(
                anyString(),
                anyString(),
                anyString(),
                anyString()
        );
    }

    @Test
    void pendingCarrierBookingIsRefreshedButNotEmailedUntilConfirmed() {
        Waybill waybill = waybill(18L, "Documents");
        WaybillParty sender = party(55L, "Ada", "sender@example.com", WaybillPartyRole.SENDER);
        WaybillEmailNotification notification = notification(65L, waybill, sender, "body");
        WaybillShipment shipment = shipment(
                waybill,
                "TRACK-18",
                "https://tracking.terminal.test/TRACK-18"
        );
        shipment.setStatus(ShipmentStatus.PENDING);
        when(notificationRepository.findByIdForUpdate(notification.getId()))
                .thenReturn(Optional.of(notification));
        when(shipmentRepository.findByWaybill_Id(waybill.getId())).thenReturn(Optional.of(shipment));
        when(shippingProvider.trackShipment(shipment.getProviderShipmentId()))
                .thenReturn(new ShipmentTrackingStatus(
                        shipment.getProviderShipmentId(),
                        ShipmentStatus.PENDING,
                        Instant.now(),
                        List.of(),
                        "TRACK-18",
                        shipment.getTrackingUrl()
                ));

        service.dispatch(notification.getId());

        assertThat(notification.getStatus()).isEqualTo(WaybillEmailNotificationStatus.PENDING);
        assertThat(notification.getAttemptCount()).isZero();
        assertThat(notification.getFailureReason()).isEqualTo(
                "Carrier booking is not confirmed yet"
        );
        verify(emailService, never()).sendEmail(
                anyString(),
                anyString(),
                anyString(),
                anyString()
        );
    }

    @Test
    void recoverableCarrierFailureDefersInsteadOfSkipping() {
        Waybill waybill = waybill(20L, "Documents");
        WaybillParty sender = party(57L, "Ada", "sender@example.com", WaybillPartyRole.SENDER);
        WaybillEmailNotification notification = notification(67L, waybill, sender, null);
        WaybillShipment shipment = shipment(waybill, "TRACK-20", null);
        shipment.setStatus(ShipmentStatus.PENDING);
        when(notificationRepository.findByIdForUpdate(notification.getId()))
                .thenReturn(Optional.of(notification));
        when(shipmentRepository.findByWaybill_Id(waybill.getId())).thenReturn(Optional.of(shipment));
        when(shippingProvider.trackShipment(shipment.getProviderShipmentId()))
                .thenReturn(new ShipmentTrackingStatus(
                        shipment.getProviderShipmentId(),
                        ShipmentStatus.FAILED,
                        Instant.now(),
                        List.of(),
                        "TRACK-20",
                        null
                ));

        service.dispatch(notification.getId());

        assertThat(notification.getStatus()).isEqualTo(WaybillEmailNotificationStatus.PENDING);
        assertThat(notification.getFailureReason()).contains("may still recover");
        verify(emailService, never()).sendEmail(
                anyString(),
                anyString(),
                anyString(),
                anyString()
        );
    }

    @Test
    void staleConfirmationCannotAuthorizeEmailFromNewerPendingState() {
        Waybill waybill = waybill(21L, "Documents");
        WaybillParty sender = party(58L, "Ada", "sender@example.com", WaybillPartyRole.SENDER);
        WaybillEmailNotification notification = notification(68L, waybill, sender, null);
        WaybillShipment shipment = shipment(waybill, "TRACK-21", null);
        shipment.setStatus(ShipmentStatus.PENDING);
        shipment.setProviderUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        when(notificationRepository.findByIdForUpdate(notification.getId()))
                .thenReturn(Optional.of(notification));
        when(shipmentRepository.findByWaybill_Id(waybill.getId())).thenReturn(Optional.of(shipment));
        when(shipmentRepository.findByWaybillIdForUpdate(waybill.getId()))
                .thenReturn(Optional.of(shipment));
        when(shippingProvider.trackShipment(shipment.getProviderShipmentId()))
                .thenReturn(new ShipmentTrackingStatus(
                        shipment.getProviderShipmentId(),
                        ShipmentStatus.CONFIRMED,
                        shipment.getProviderUpdatedAt().minusMinutes(1).toInstant(ZoneOffset.UTC),
                        List.of(),
                        "STALE-TRACK-21",
                        "https://tracking.terminal.test/STALE-TRACK-21"
                ));

        service.dispatch(notification.getId());

        assertThat(notification.getStatus()).isEqualTo(WaybillEmailNotificationStatus.PENDING);
        assertThat(notification.getFailureReason()).contains("older than stored state");
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.PENDING);
        assertThat(shipment.getTrackingUrl()).isNull();
        verify(emailService, never()).sendEmail(
                anyString(),
                anyString(),
                anyString(),
                anyString()
        );
    }

    @Test
    void ambiguousEmailOlderThanProviderIdempotencyWindowRequiresManualReview() {
        Waybill waybill = waybill(19L, "Documents");
        WaybillParty sender = party(56L, "Ada", "sender@example.com", WaybillPartyRole.SENDER);
        WaybillEmailNotification notification = notification(66L, waybill, sender, "body");
        notification.setFirstAttemptAt(LocalDateTime.now(ZoneOffset.UTC).minusHours(24));
        when(notificationRepository.findByIdForUpdate(notification.getId()))
                .thenReturn(Optional.of(notification));

        service.dispatch(notification.getId());

        assertThat(notification.getStatus()).isEqualTo(WaybillEmailNotificationStatus.FAILED);
        assertThat(notification.getFailureReason()).contains("manual review");
        verify(emailService, never()).sendEmail(
                anyString(),
                anyString(),
                anyString(),
                anyString()
        );
    }

    private Waybill waybill(Long id, String description) {
        return Waybill.builder()
                .id(id)
                .description(description)
                .status(WaybillStatus.READY_FOR_DELIVERY)
                .build();
    }

    private WaybillShipment shipment(
            Waybill waybill,
            String trackingReference,
            String trackingUrl
    ) {
        return WaybillShipment.builder()
                .waybill(waybill)
                .provider("TERMINAL_AFRICA")
                .providerShipmentId("shipment-" + waybill.getId())
                .status(ShipmentStatus.CONFIRMED)
                .trackingReference(trackingReference)
                .trackingUrl(trackingUrl)
                .build();
    }

    private WaybillParty party(
            Long id,
            String firstName,
            String email,
            WaybillPartyRole role
    ) {
        return WaybillParty.builder()
                .id(id)
                .firstName(firstName)
                .email(email)
                .role(role)
                .build();
    }

    private WaybillEmailNotification notification(
            Long id,
            Waybill waybill,
            WaybillParty party,
            String htmlBody
    ) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return WaybillEmailNotification.builder()
                .id(id)
                .waybill(waybill)
                .party(party)
                .notificationType(WaybillEmailNotificationType.AWAITING_PICKUP)
                .status(WaybillEmailNotificationStatus.PENDING)
                .recipientEmail(party.getEmail())
                .subject("Payment confirmed - package entered pickup stage")
                .htmlBody(htmlBody)
                .nextAttemptAt(now.minusSeconds(1))
                .createdAt(now.minusMinutes(1))
                .updatedAt(now.minusMinutes(1))
                .build();
    }
}
