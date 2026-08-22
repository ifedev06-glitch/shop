package com.example.shopsecure.service;

import com.example.shopsecure.entity.ProcessedWebhookEvent;
import com.example.shopsecure.entity.ShippingQuoteStatus;
import com.example.shopsecure.entity.User;
import com.example.shopsecure.entity.Waybill;
import com.example.shopsecure.entity.WaybillParcel;
import com.example.shopsecure.entity.WaybillParty;
import com.example.shopsecure.entity.WaybillPartyRole;
import com.example.shopsecure.entity.WaybillPayerType;
import com.example.shopsecure.entity.WaybillPaymentAttempt;
import com.example.shopsecure.entity.WaybillPaymentAttemptStatus;
import com.example.shopsecure.entity.WaybillPaymentStatus;
import com.example.shopsecure.entity.WaybillShipment;
import com.example.shopsecure.entity.WaybillShippingQuote;
import com.example.shopsecure.entity.WaybillStatus;
import com.example.shopsecure.event.WaybillCarrierCancellationConfirmedEvent;
import com.example.shopsecure.event.WaybillPickupArrangedEvent;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.provider.shipping.AddressValidationResult;
import com.example.shopsecure.provider.shipping.ShipmentStatus;
import com.example.shopsecure.provider.shipping.ShipmentStatusUpdatedEvent;
import com.example.shopsecure.provider.shipping.ShipmentTrackingStatus;
import com.example.shopsecure.provider.shipping.ShipmentResponse;
import com.example.shopsecure.provider.shipping.ShippingProvider;
import com.example.shopsecure.provider.shipping.ShippingRateOption;
import com.example.shopsecure.provider.shipping.ShipmentRequest;
import com.example.shopsecure.repository.ProcessedWebhookEventRepository;
import com.example.shopsecure.repository.WaybillParcelRepository;
import com.example.shopsecure.repository.WaybillPartyRepository;
import com.example.shopsecure.repository.WaybillPaymentAttemptRepository;
import com.example.shopsecure.repository.WaybillPaymentReceiptRepository;
import com.example.shopsecure.repository.WaybillRepository;
import com.example.shopsecure.repository.WaybillShipmentRepository;
import com.example.shopsecure.repository.WaybillShippingQuoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WaybillShippingServiceTest {

    private ShippingProvider shippingProvider;
    private ShippingProviderRoutingService shippingRoutingService;
    private WaybillRepository waybillRepository;
    private WaybillPartyRepository partyRepository;
    private WaybillParcelRepository parcelRepository;
    private WaybillPaymentAttemptRepository attemptRepository;
    private WaybillPaymentReceiptRepository receiptRepository;
    private WaybillShipmentRepository shipmentRepository;
    private WaybillShippingQuoteRepository quoteRepository;
    private ProcessedWebhookEventRepository webhookEventRepository;
    private WaybillPricingService pricingService;
    private WaybillStatusService statusService;
    private OrderService orderService;
    private WaybillAccessService accessService;
    private WaybillNotificationService notificationService;
    private ApplicationEventPublisher eventPublisher;
    private WaybillShippingService service;
    private User creator;

    @BeforeEach
    void setUp() {
        shippingProvider = mock(ShippingProvider.class);
        shippingRoutingService = mock(ShippingProviderRoutingService.class);
        waybillRepository = mock(WaybillRepository.class);
        partyRepository = mock(WaybillPartyRepository.class);
        parcelRepository = mock(WaybillParcelRepository.class);
        attemptRepository = mock(WaybillPaymentAttemptRepository.class);
        receiptRepository = mock(WaybillPaymentReceiptRepository.class);
        shipmentRepository = mock(WaybillShipmentRepository.class);
        quoteRepository = mock(WaybillShippingQuoteRepository.class);
        webhookEventRepository = mock(ProcessedWebhookEventRepository.class);
        pricingService = mock(WaybillPricingService.class);
        statusService = mock(WaybillStatusService.class);
        orderService = mock(OrderService.class);
        accessService = mock(WaybillAccessService.class);
        notificationService = mock(WaybillNotificationService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new WaybillShippingService(
                shippingProvider,
                shippingRoutingService,
                waybillRepository,
                partyRepository,
                parcelRepository,
                attemptRepository,
                receiptRepository,
                shipmentRepository,
                quoteRepository,
                webhookEventRepository,
                pricingService,
                statusService,
                orderService,
                accessService,
                notificationService,
                eventPublisher
        );
        ReflectionTestUtils.setField(service, "quoteTtl", Duration.ofMinutes(15));
        ReflectionTestUtils.setField(service, "maxBookingAttempts", 5);
        ReflectionTestUtils.setField(service, "bookingRetryDelay", Duration.ofMinutes(5));
        creator = User.builder().id(1L).email("creator@example.com").build();
        when(orderService.currentUser()).thenReturn(creator);
        when(shippingRoutingService.require(anyString())).thenReturn(shippingProvider);
        when(shippingProvider.supportsPreBookingTracking()).thenReturn(true);
        when(shippingProvider.draftExistsRemotely()).thenReturn(true);
        doAnswer(invocation -> {
            Waybill waybill = invocation.getArgument(0);
            waybill.setStatus(invocation.getArgument(1));
            return true;
        }).when(statusService).transition(any(Waybill.class), any(WaybillStatus.class), anyString());
        when(quoteRepository.save(any(WaybillShippingQuote.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void expiredUnfundedSelectionResetsAndSupersedesOpenAttemptsImmediately() {
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);
        Waybill waybill = waybill(WaybillStatus.AWAITING_PAYMENT, WaybillPaymentStatus.PENDING);
        waybill.setProviderDeliveryFee(new BigDecimal("80.00"));
        waybill.setDeliveryMarkup(new BigDecimal("10.00"));
        waybill.setDeliveryFee(new BigDecimal("90.00"));
        waybill.setServiceFee(new BigDecimal("10.00"));
        waybill.setTotalAmount(new BigDecimal("100.00"));
        waybill.setPaymentDueAt(before.minusMinutes(1));
        WaybillShipment shipment = shipment(waybill, ShipmentStatus.DRAFT, before.minusMinutes(5));
        WaybillShippingQuote selected = quote(shipment, ShippingQuoteStatus.SELECTED, before.minusMinutes(1));
        WaybillPaymentAttempt attempt = WaybillPaymentAttempt.builder()
                .id(40L)
                .waybill(waybill)
                .status(WaybillPaymentAttemptStatus.INITIALIZED)
                .createdAt(before.minusMinutes(5))
                .updatedAt(before.minusMinutes(5))
                .build();
        stubQuoteCreation(waybill, shipment);
        when(quoteRepository.findByWaybillIdAndStatusForUpdate(
                waybill.getId(),
                ShippingQuoteStatus.SELECTED
        )).thenReturn(Optional.of(selected));
        when(attemptRepository.findByWaybill_IdAndStatusInOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of(attempt));

        var quotes = service.createQuotes(waybill.getPublicId());

        assertThat(quotes).hasSize(1);
        assertThat(selected.getStatus()).isEqualTo(ShippingQuoteStatus.EXPIRED);
        assertThat(attempt.getStatus()).isEqualTo(WaybillPaymentAttemptStatus.SUPERSEDED);
        assertThat(attempt.getNextRecheckAt()).isBetween(before, LocalDateTime.now(ZoneOffset.UTC));
        assertThat(waybill.getStatus()).isEqualTo(WaybillStatus.AWAITING_QUOTE);
        assertThat(waybill.getPaymentStatus()).isEqualTo(WaybillPaymentStatus.NOT_STARTED);
        assertThat(waybill.getPaymentDueAt()).isNull();
        assertThat(waybill.getTotalAmount()).isNull();
        assertThat(waybill.getProviderDeliveryFee()).isNull();
        verify(attemptRepository).save(attempt);
    }

    @Test
    void expiredSelectionWithSuccessReceiptMovesToPaymentReview() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Waybill waybill = waybill(WaybillStatus.AWAITING_PAYMENT, WaybillPaymentStatus.PENDING);
        waybill.setPaymentDueAt(now.minusMinutes(1));
        WaybillShipment shipment = shipment(waybill, ShipmentStatus.DRAFT, now.minusMinutes(2));
        WaybillShippingQuote selected = quote(shipment, ShippingQuoteStatus.SELECTED, now.minusMinutes(1));
        when(waybillRepository.findByPublicIdForUpdate(waybill.getPublicId()))
                .thenReturn(Optional.of(waybill));
        when(quoteRepository.findByWaybillIdAndStatusForUpdate(
                waybill.getId(),
                ShippingQuoteStatus.SELECTED
        )).thenReturn(Optional.of(selected));
        when(receiptRepository.existsByWaybill_IdAndProviderStatus(any(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.createQuotes(waybill.getPublicId()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Expired waybill quote requires payment review");

        assertThat(waybill.getStatus()).isEqualTo(WaybillStatus.PAYMENT_REVIEW);
        assertThat(waybill.getPaymentStatus()).isEqualTo(WaybillPaymentStatus.REVIEW);
        verify(shippingProvider, never()).getRates(anyString(), anyString());
    }

    @Test
    void staleWebhookCannotMutateStoredProviderState() {
        LocalDateTime storedAt = LocalDateTime.of(2026, 8, 15, 12, 0);
        Waybill waybill = waybill(WaybillStatus.READY_FOR_DELIVERY, WaybillPaymentStatus.CONFIRMED);
        WaybillShipment shipment = shipment(waybill, ShipmentStatus.IN_TRANSIT, storedAt);
        stubWebhookLocks(waybill, shipment);

        service.applyWebhookEvent(new ShipmentStatusUpdatedEvent(
                shipment.getProviderShipmentId(),
                ShipmentStatus.CONFIRMED,
                Instant.parse("2026-08-15T11:00:00Z"),
                true
        ), "old-event");

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(shipment.getProviderUpdatedAt()).isEqualTo(storedAt);
        verify(shipmentRepository, never()).save(shipment);
        verify(webhookEventRepository).save(any(ProcessedWebhookEvent.class));
    }

    @Test
    void newerDeliveryAfterCancellationRecordsConflictWithoutCompleting() {
        LocalDateTime storedAt = LocalDateTime.of(2026, 8, 15, 12, 0);
        Waybill waybill = waybill(WaybillStatus.CANCELLED, WaybillPaymentStatus.NOT_STARTED);
        waybill.setCancellationRequestedAt(storedAt.minusMinutes(5));
        WaybillShipment shipment = shipment(waybill, ShipmentStatus.CANCELLED, storedAt);
        stubWebhookLocks(waybill, shipment);

        service.applyWebhookEvent(new ShipmentStatusUpdatedEvent(
                shipment.getProviderShipmentId(),
                ShipmentStatus.DELIVERED,
                Instant.parse("2026-08-15T13:00:00Z"),
                true
        ), "delivered-after-cancel");

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(waybill.getStatus()).isEqualTo(WaybillStatus.MANUAL_REVIEW);
        assertThat(waybill.getCompletedAt()).isNull();
    }

    @Test
    void deliveryDuringCancellationStopsInManualReview() {
        LocalDateTime storedAt = LocalDateTime.of(2026, 8, 15, 12, 0);
        Waybill waybill = waybill(WaybillStatus.CANCELLATION_PENDING, WaybillPaymentStatus.CONFIRMED);
        WaybillShipment shipment = shipment(waybill, ShipmentStatus.IN_TRANSIT, storedAt);
        stubWebhookLocks(waybill, shipment);

        service.applyWebhookEvent(new ShipmentStatusUpdatedEvent(
                shipment.getProviderShipmentId(),
                ShipmentStatus.DELIVERED,
                Instant.parse("2026-08-15T13:00:00Z"),
                true
        ), "delivered-during-cancel");

        assertThat(waybill.getStatus()).isEqualTo(WaybillStatus.MANUAL_REVIEW);
        assertThat(waybill.getCompletedAt()).isNull();
    }

    @Test
    void bookingFailureRetainsActualProviderShipmentStatus() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Waybill waybill = waybill(WaybillStatus.SHIPMENT_BOOKING, WaybillPaymentStatus.CONFIRMED);
        WaybillShipment shipment = shipment(waybill, ShipmentStatus.DRAFT, now.minusMinutes(1));
        WaybillShippingQuote selected = quote(shipment, ShippingQuoteStatus.SELECTED, now.plusMinutes(5));
        when(waybillRepository.findByIdForUpdate(waybill.getId())).thenReturn(Optional.of(waybill));
        when(shipmentRepository.findByWaybillIdForUpdate(waybill.getId()))
                .thenReturn(Optional.of(shipment));
        when(shippingProvider.trackShipment(shipment.getProviderShipmentId())).thenReturn(
                new ShipmentTrackingStatus(
                        shipment.getProviderShipmentId(),
                        ShipmentStatus.DRAFT,
                        now.toInstant(ZoneOffset.UTC),
                        List.of(),
                        null,
                        null
                )
        );
        when(quoteRepository.findByWaybillIdAndStatusForUpdate(
                waybill.getId(),
                ShippingQuoteStatus.SELECTED
        )).thenReturn(Optional.of(selected));
        when(shippingProvider.getWalletBalance()).thenThrow(new BusinessException("provider unavailable"));

        service.arrangePickup(waybill.getId());

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.DRAFT);
        assertThat(shipment.getFailureReason()).isEqualTo("Shipment booking attempt failed");
        assertThat(waybill.getStatus()).isEqualTo(WaybillStatus.SHIPPING_FAILED);
    }

    @Test
    void finalChowdeckFailureSchedulesStateOnlyReconciliationBeforeManualReview() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Waybill waybill = waybill(WaybillStatus.SHIPMENT_BOOKING, WaybillPaymentStatus.CONFIRMED);
        WaybillShipment shipment = shipment(waybill, ShipmentStatus.DRAFT, now.minusMinutes(2));
        shipment.setProvider("CHOWDECK");
        shipment.setBookingAttempt(4);
        WaybillShippingQuote selected = quote(shipment, ShippingQuoteStatus.SELECTED, now.plusMinutes(5));
        when(waybillRepository.findByIdForUpdate(waybill.getId())).thenReturn(Optional.of(waybill));
        when(shipmentRepository.findByWaybillIdForUpdate(waybill.getId()))
                .thenReturn(Optional.of(shipment));
        when(quoteRepository.findByWaybillIdAndStatusForUpdate(
                waybill.getId(),
                ShippingQuoteStatus.SELECTED
        )).thenReturn(Optional.of(selected));
        when(shippingProvider.getWalletBalance()).thenThrow(new BusinessException("provider unavailable"));

        service.arrangePickup(waybill.getId());

        assertThat(shipment.getBookingAttempt()).isEqualTo(5);
        assertThat(shipment.getNextBookingAttemptAt()).isNotNull();
        assertThat(waybill.getStatus()).isEqualTo(WaybillStatus.SHIPPING_FAILED);

        shipment.setNextBookingAttemptAt(now.minusSeconds(1));
        when(shippingProvider.trackShipment(shipment.getProviderShipmentId())).thenReturn(
                new ShipmentTrackingStatus(
                        shipment.getProviderShipmentId(),
                        ShipmentStatus.CONFIRMED,
                        now.toInstant(ZoneOffset.UTC),
                        List.of(),
                        shipment.getProviderShipmentId(),
                        null
                )
        );

        service.arrangePickup(waybill.getId());

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CONFIRMED);
        assertThat(waybill.getStatus()).isEqualTo(WaybillStatus.READY_FOR_DELIVERY);
    }

    @Test
    void successfulBookingPreservesTrackingUrlAndPublishesPickupNotification() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Waybill waybill = waybill(WaybillStatus.SHIPMENT_BOOKING, WaybillPaymentStatus.CONFIRMED);
        WaybillShipment shipment = shipment(waybill, ShipmentStatus.DRAFT, now.minusMinutes(1));
        shipment.setTrackingReference("TRACK-20");
        shipment.setTrackingUrl("https://tracking.terminal.test/TRACK-20");
        WaybillShippingQuote selected = quote(shipment, ShippingQuoteStatus.SELECTED, now.plusMinutes(5));
        when(waybillRepository.findByIdForUpdate(waybill.getId())).thenReturn(Optional.of(waybill));
        when(shipmentRepository.findByWaybillIdForUpdate(waybill.getId()))
                .thenReturn(Optional.of(shipment));
        when(shippingProvider.trackShipment(shipment.getProviderShipmentId())).thenReturn(
                new ShipmentTrackingStatus(
                        shipment.getProviderShipmentId(),
                        ShipmentStatus.DRAFT,
                        now.toInstant(ZoneOffset.UTC),
                        List.of(),
                        null,
                        null
                )
        );
        when(quoteRepository.findByWaybillIdAndStatusForUpdate(
                waybill.getId(),
                ShippingQuoteStatus.SELECTED
        )).thenReturn(Optional.of(selected));
        when(shippingProvider.getWalletBalance()).thenReturn(new BigDecimal("1000.00"));
        when(shippingProvider.arrangePickup(
                shipment.getProviderShipmentId(),
                selected.getProviderRateId()
        )).thenReturn(new ShipmentResponse(
                shipment.getProviderShipmentId(),
                ShipmentStatus.CONFIRMED,
                null,
                null,
                now.plusMinutes(1).toInstant(ZoneOffset.UTC)
        ));

        service.arrangePickup(waybill.getId());

        assertThat(waybill.getStatus()).isEqualTo(WaybillStatus.READY_FOR_DELIVERY);
        assertThat(shipment.getTrackingReference()).isEqualTo("TRACK-20");
        assertThat(shipment.getTrackingUrl()).isEqualTo("https://tracking.terminal.test/TRACK-20");
        verify(notificationService).enqueueAwaitingPickup(waybill, shipment);
        verify(eventPublisher).publishEvent(any(WaybillPickupArrangedEvent.class));
    }

    @Test
    void mismatchedPickupResponseCannotPublishAnotherShipmentsTrackingLink() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Waybill waybill = waybill(WaybillStatus.SHIPMENT_BOOKING, WaybillPaymentStatus.CONFIRMED);
        WaybillShipment shipment = shipment(waybill, ShipmentStatus.DRAFT, now.minusMinutes(1));
        WaybillShippingQuote selected = quote(shipment, ShippingQuoteStatus.SELECTED, now.plusMinutes(5));
        when(waybillRepository.findByIdForUpdate(waybill.getId())).thenReturn(Optional.of(waybill));
        when(shipmentRepository.findByWaybillIdForUpdate(waybill.getId()))
                .thenReturn(Optional.of(shipment));
        when(shippingProvider.trackShipment(shipment.getProviderShipmentId())).thenReturn(
                new ShipmentTrackingStatus(
                        shipment.getProviderShipmentId(),
                        ShipmentStatus.DRAFT,
                        now.toInstant(ZoneOffset.UTC),
                        List.of(),
                        null,
                        null
                )
        );
        when(quoteRepository.findByWaybillIdAndStatusForUpdate(
                waybill.getId(),
                ShippingQuoteStatus.SELECTED
        )).thenReturn(Optional.of(selected));
        when(shippingProvider.getWalletBalance()).thenReturn(new BigDecimal("1000.00"));
        when(shippingProvider.arrangePickup(
                shipment.getProviderShipmentId(),
                selected.getProviderRateId()
        )).thenReturn(new ShipmentResponse(
                "OTHER-SHIPMENT",
                ShipmentStatus.CONFIRMED,
                "OTHER-TRACKING",
                "https://tracking.terminal.test/OTHER-TRACKING",
                now.plusMinutes(1).toInstant(ZoneOffset.UTC)
        ));

        service.arrangePickup(waybill.getId());

        assertThat(waybill.getStatus()).isEqualTo(WaybillStatus.SHIPPING_FAILED);
        assertThat(shipment.getTrackingUrl()).isNull();
        verify(notificationService, never()).enqueueAwaitingPickup(any(), any());
        verify(eventPublisher, never()).publishEvent(any(WaybillPickupArrangedEvent.class));
    }

    @Test
    void mismatchedTrackingResponseIsRejected() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Waybill waybill = waybill(WaybillStatus.READY_FOR_DELIVERY, WaybillPaymentStatus.CONFIRMED);
        WaybillShipment shipment = shipment(waybill, ShipmentStatus.CONFIRMED, now.minusMinutes(1));
        when(waybillRepository.findByPublicIdForUpdate(waybill.getPublicId()))
                .thenReturn(Optional.of(waybill));
        when(shipmentRepository.findByWaybillIdForUpdate(waybill.getId()))
                .thenReturn(Optional.of(shipment));
        when(shippingProvider.trackShipment(shipment.getProviderShipmentId())).thenReturn(
                new ShipmentTrackingStatus(
                        "OTHER-SHIPMENT",
                        ShipmentStatus.IN_TRANSIT,
                        now.toInstant(ZoneOffset.UTC),
                        List.of(),
                        "OTHER-TRACKING",
                        "https://tracking.terminal.test/OTHER-TRACKING"
                )
        );

        assertThatThrownBy(() -> service.refreshTracking(waybill.getPublicId()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Shipping provider returned a mismatched tracking response");

        assertThat(shipment.getTrackingUrl()).isNull();
        verify(shipmentRepository, never()).save(shipment);
    }

    @Test
    void scheduledChowdeckTrackingCompletesDeliveredWaybill() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Waybill waybill = waybill(WaybillStatus.READY_FOR_DELIVERY, WaybillPaymentStatus.CONFIRMED);
        WaybillShipment shipment = shipment(waybill, ShipmentStatus.CONFIRMED, now.minusMinutes(2));
        shipment.setProvider("CHOWDECK");
        when(waybillRepository.findByIdForUpdate(waybill.getId())).thenReturn(Optional.of(waybill));
        when(shipmentRepository.findByWaybillIdForUpdate(waybill.getId()))
                .thenReturn(Optional.of(shipment));
        when(shippingProvider.trackShipment(shipment.getProviderShipmentId())).thenReturn(
                new ShipmentTrackingStatus(
                        shipment.getProviderShipmentId(),
                        ShipmentStatus.DELIVERED,
                        now.toInstant(ZoneOffset.UTC),
                        List.of(),
                        shipment.getProviderShipmentId(),
                        null
                )
        );

        service.reconcileChowdeckTracking(waybill.getId());

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(waybill.getStatus()).isEqualTo(WaybillStatus.COMPLETED);
        assertThat(waybill.getCompletedAt()).isEqualTo(now);
        verify(shipmentRepository).save(shipment);
        verify(waybillRepository).save(waybill);
    }

    @Test
    void mismatchedCancellationResponseIsRejected() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Waybill waybill = waybill(WaybillStatus.CANCELLATION_PENDING, WaybillPaymentStatus.CONFIRMED);
        WaybillShipment shipment = shipment(waybill, ShipmentStatus.CONFIRMED, now.minusMinutes(1));
        when(waybillRepository.findByIdForUpdate(waybill.getId())).thenReturn(Optional.of(waybill));
        when(shipmentRepository.findByWaybillIdForUpdate(waybill.getId()))
                .thenReturn(Optional.of(shipment));
        when(shippingProvider.cancelShipment(shipment.getProviderShipmentId())).thenReturn(
                new ShipmentResponse(
                        "OTHER-SHIPMENT",
                        ShipmentStatus.CANCELLED,
                        null,
                        null,
                        now.toInstant(ZoneOffset.UTC)
                )
        );

        assertThatThrownBy(() -> service.cancelProviderShipment(waybill.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Shipping provider returned a mismatched shipment response");

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CONFIRMED);
    }

    @Test
    void carrierCancelledWebhookPublishesFinalizationAfterStatePersistence() {
        LocalDateTime storedAt = LocalDateTime.of(2026, 8, 15, 12, 0);
        Waybill waybill = waybill(WaybillStatus.CANCELLATION_PENDING, WaybillPaymentStatus.CONFIRMED);
        WaybillShipment shipment = shipment(waybill, ShipmentStatus.CONFIRMED, storedAt);
        stubWebhookLocks(waybill, shipment);

        service.applyWebhookEvent(new ShipmentStatusUpdatedEvent(
                shipment.getProviderShipmentId(),
                ShipmentStatus.CANCELLED,
                Instant.parse("2026-08-15T13:00:00Z"),
                true
        ), "carrier-cancelled");

        ArgumentCaptor<WaybillCarrierCancellationConfirmedEvent> event = ArgumentCaptor.forClass(
                WaybillCarrierCancellationConfirmedEvent.class
        );
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().waybillId()).isEqualTo(waybill.getId());
    }

    private void stubQuoteCreation(Waybill waybill, WaybillShipment shipment) {
        when(waybillRepository.findByPublicIdForUpdate(waybill.getPublicId()))
                .thenReturn(Optional.of(waybill));
        when(partyRepository.findByWaybill_IdAndRole(waybill.getId(), WaybillPartyRole.SENDER))
                .thenReturn(Optional.of(party(waybill, WaybillPartyRole.SENDER)));
        when(partyRepository.findByWaybill_IdAndRole(waybill.getId(), WaybillPartyRole.RECIPIENT))
                .thenReturn(Optional.of(party(waybill, WaybillPartyRole.RECIPIENT)));
        when(parcelRepository.findByWaybill_Id(waybill.getId())).thenReturn(Optional.of(
                WaybillParcel.builder()
                        .waybill(waybill)
                        .weightKg(BigDecimal.ONE)
                        .quantity(1)
                        .parcelType("parcel")
                        .build()
        ));
        when(shipmentRepository.findByWaybillIdForUpdate(waybill.getId()))
                .thenReturn(Optional.of(shipment));
        when(quoteRepository.findByShipment_IdAndStatusOrderByDeliveryFeeAsc(
                shipment.getId(),
                ShippingQuoteStatus.AVAILABLE
        )).thenReturn(List.of());
        when(shippingProvider.validateAddress(any())).thenReturn(new AddressValidationResult(true, null));
        when(shippingRoutingService.quote(
                org.mockito.ArgumentMatchers.eq(shipment.getProvider()),
                org.mockito.ArgumentMatchers.eq(shipment.getProviderShipmentId()),
                any(ShipmentRequest.class),
                org.mockito.ArgumentMatchers.eq("NGN")
        )).thenReturn(new ShippingProvider.ShippingQuoteResult(List.of(
                new ShippingRateOption(
                        "rate-new",
                        "internal-new",
                        "Carrier",
                        null,
                        "Next day",
                        new BigDecimal("80.00"),
                        "NGN",
                        null,
                        null
                )
        ), null));
        when(pricingService.price(waybill, new BigDecimal("80.00"))).thenReturn(
                new WaybillPricingService.Pricing(
                        new BigDecimal("80.00"),
                        new BigDecimal("10.00"),
                        new BigDecimal("90.00"),
                        new BigDecimal("10.00"),
                        new BigDecimal("100.00"),
                        "NGN"
                )
        );
        when(quoteRepository.findByShipment_IdAndProviderRateId(shipment.getId(), "rate-new"))
                .thenReturn(Optional.empty());
    }

    private void stubWebhookLocks(Waybill waybill, WaybillShipment shipment) {
        when(shipmentRepository.findByProviderShipmentId(shipment.getProviderShipmentId()))
                .thenReturn(Optional.of(shipment));
        when(waybillRepository.findByIdForUpdate(waybill.getId())).thenReturn(Optional.of(waybill));
        when(shipmentRepository.findByWaybillIdForUpdate(waybill.getId()))
                .thenReturn(Optional.of(shipment));
    }

    private Waybill waybill(WaybillStatus status, WaybillPaymentStatus paymentStatus) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return Waybill.builder()
                .id(10L)
                .publicId("waybill-10")
                .creator(creator)
                .creatorRole(WaybillPartyRole.SENDER)
                .payerType(WaybillPayerType.CREATOR)
                .description("Parcel")
                .declaredValue(new BigDecimal("1000.00"))
                .currency("NGN")
                .status(status)
                .paymentStatus(paymentStatus)
                .createdAt(now.minusHours(1))
                .updatedAt(now.minusHours(1))
                .build();
    }

    private WaybillShipment shipment(
            Waybill waybill,
            ShipmentStatus status,
            LocalDateTime providerUpdatedAt
    ) {
        return WaybillShipment.builder()
                .id(20L)
                .waybill(waybill)
                .provider("TERMINAL_AFRICA")
                .providerShipmentId("SH-20")
                .status(status)
                .providerUpdatedAt(providerUpdatedAt)
                .createdAt(providerUpdatedAt.minusMinutes(1))
                .updatedAt(providerUpdatedAt)
                .build();
    }

    private WaybillShippingQuote quote(
            WaybillShipment shipment,
            ShippingQuoteStatus status,
            LocalDateTime expiresAt
    ) {
        return WaybillShippingQuote.builder()
                .id(30L)
                .shipment(shipment)
                .providerRateId("rate-old")
                .carrierName("Carrier")
                .providerAmount(new BigDecimal("80.00"))
                .deliveryMarkup(new BigDecimal("10.00"))
                .deliveryFee(new BigDecimal("90.00"))
                .serviceFee(new BigDecimal("10.00"))
                .totalAmount(new BigDecimal("100.00"))
                .currency("NGN")
                .status(status)
                .expiresAt(expiresAt)
                .createdAt(expiresAt.minusMinutes(15))
                .build();
    }

    private WaybillParty party(Waybill waybill, WaybillPartyRole role) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return WaybillParty.builder()
                .waybill(waybill)
                .role(role)
                .firstName("Test")
                .lastName("Person")
                .email(role.name().toLowerCase() + "@example.com")
                .phoneNumber("+2348012345678")
                .addressLine1("1 Main Street")
                .city("Lagos")
                .state("Lagos")
                .country("NG")
                .postalCode("100001")
                .detailsCompletedAt(now.minusMinutes(1))
                .build();
    }
}
