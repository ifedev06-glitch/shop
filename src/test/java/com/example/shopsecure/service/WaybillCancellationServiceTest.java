package com.example.shopsecure.service;

import com.example.shopsecure.dto.response.WaybillRefundResponse;
import com.example.shopsecure.entity.User;
import com.example.shopsecure.entity.Waybill;
import com.example.shopsecure.entity.WaybillPaymentAttempt;
import com.example.shopsecure.entity.WaybillPaymentAttemptStatus;
import com.example.shopsecure.entity.WaybillPaymentClassification;
import com.example.shopsecure.entity.WaybillPaymentReceipt;
import com.example.shopsecure.entity.WaybillPaymentStatus;
import com.example.shopsecure.entity.WaybillShipment;
import com.example.shopsecure.entity.WaybillStatus;
import com.example.shopsecure.provider.payment.PaymentTransactionStatus;
import com.example.shopsecure.provider.shipping.ShipmentResponse;
import com.example.shopsecure.provider.shipping.ShipmentStatus;
import com.example.shopsecure.repository.WaybillPaymentAttemptRepository;
import com.example.shopsecure.repository.WaybillPaymentReceiptRepository;
import com.example.shopsecure.repository.WaybillRefundRepository;
import com.example.shopsecure.repository.WaybillRepository;
import com.example.shopsecure.repository.WaybillShipmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WaybillCancellationServiceTest {

    private WaybillRepository waybillRepository;
    private WaybillShipmentRepository shipmentRepository;
    private WaybillPaymentAttemptRepository attemptRepository;
    private WaybillPaymentReceiptRepository receiptRepository;
    private WaybillRefundRepository refundRepository;
    private WaybillShippingService shippingService;
    private WaybillRefundService refundService;
    private WaybillStatusService statusService;
    private OrderService orderService;
    private WaybillCancellationService service;
    private User creator;

    @BeforeEach
    void setUp() {
        waybillRepository = mock(WaybillRepository.class);
        shipmentRepository = mock(WaybillShipmentRepository.class);
        attemptRepository = mock(WaybillPaymentAttemptRepository.class);
        receiptRepository = mock(WaybillPaymentReceiptRepository.class);
        refundRepository = mock(WaybillRefundRepository.class);
        shippingService = mock(WaybillShippingService.class);
        refundService = mock(WaybillRefundService.class);
        statusService = mock(WaybillStatusService.class);
        orderService = mock(OrderService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        service = new WaybillCancellationService(
                waybillRepository,
                shipmentRepository,
                attemptRepository,
                receiptRepository,
                refundRepository,
                shippingService,
                mock(WaybillAccessService.class),
                refundService,
                statusService,
                orderService,
                transactionManager
        );
        creator = User.builder().id(1L).build();
        when(orderService.currentUser()).thenReturn(creator);
        when(refundRepository.findByWaybill_IdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(receiptRepository.findByWaybill_IdAndProviderStatusOrderByCreatedAtAsc(
                any(),
                eq(PaymentTransactionStatus.SUCCESS)
        )).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "reconciliationBatchSize", 25);
        ReflectionTestUtils.setField(service, "maxCancellationAttempts", 5);
        ReflectionTestUtils.setField(service, "cancellationRetryDelay", Duration.ofMinutes(5));
        doAnswer(invocation -> {
            Waybill waybill = invocation.getArgument(0);
            waybill.setStatus(invocation.getArgument(1));
            return true;
        }).when(statusService).transition(any(Waybill.class), any(WaybillStatus.class), any());
    }

    @Test
    void persistsInitialAttemptAndLeaseBeforeProviderDispatch() {
        Waybill waybill = cancellableWaybill();
        WaybillShipment shipment = shipment(waybill, ShipmentStatus.CONFIRMED);
        stubLockedWaybill(waybill, shipment);
        when(shippingService.cancelProviderShipment(waybill.getId())).thenReturn(
                response(shipment, ShipmentStatus.PENDING)
        );
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);

        service.request(waybill.getPublicId(), "cancel-key", "Customer request");

        assertThat(shipment.getCancellationAttempt()).isEqualTo(1);
        assertThat(shipment.getNextCancellationAttemptAt())
                .isAfterOrEqualTo(before.plusMinutes(5))
                .isBeforeOrEqualTo(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5).plusSeconds(1));
        InOrder persistenceBeforeDispatch = inOrder(shipmentRepository, shippingService);
        persistenceBeforeDispatch.verify(shipmentRepository).save(shipment);
        persistenceBeforeDispatch.verify(shippingService).cancelProviderShipment(waybill.getId());
    }

    @Test
    void futureDueSameKeyRetryReplaysWithoutProviderDispatch() {
        Waybill waybill = pendingCancellation();
        WaybillShipment shipment = shipment(waybill, ShipmentStatus.CONFIRMED);
        shipment.setCancellationAttempt(1);
        shipment.setNextCancellationAttemptAt(LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
        stubLockedWaybill(waybill, shipment);

        service.request(waybill.getPublicId(), "cancel-key", null);

        verify(shippingService, never()).cancelProviderShipment(any());
        assertThat(waybill.getStatus()).isEqualTo(WaybillStatus.CANCELLATION_PENDING);
        assertThat(shipment.getCancellationAttempt()).isEqualTo(1);
    }

    @Test
    void dueSchedulerRetriesWithBoundedBatch() {
        Waybill waybill = pendingCancellation();
        WaybillShipment shipment = shipment(waybill, ShipmentStatus.PENDING);
        shipment.setCancellationAttempt(1);
        shipment.setNextCancellationAttemptAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        stubScheduledWaybill(waybill, shipment);
        when(shippingService.cancelProviderShipment(waybill.getId())).thenReturn(
                response(shipment, ShipmentStatus.PENDING)
        );
        ReflectionTestUtils.setField(service, "reconciliationBatchSize", 500);
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);

        service.reconcileCancellations();

        assertThat(shipment.getCancellationAttempt()).isEqualTo(2);
        assertThat(shipment.getNextCancellationAttemptAt())
                .isAfterOrEqualTo(before.plusMinutes(10));
        verify(shippingService).cancelProviderShipment(waybill.getId());
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(waybillRepository).findWaybillIdsDueForCancellation(
                eq(WaybillStatus.CANCELLATION_PENDING),
                anyCollection(),
                any(LocalDateTime.class),
                pageable.capture()
        );
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    void schedulerFinalizesPendingCancellationWhenShipmentWasNeverPersisted() {
        Waybill waybill = pendingCancellation();
        stubScheduledWaybill(waybill, null);

        service.reconcileCancellations();

        assertThat(waybill.getStatus()).isEqualTo(WaybillStatus.CANCELLED);
        verify(shippingService, never()).cancelProviderShipment(any());
    }

    @Test
    void providerFailureRetainsPendingAndSchedulesLinearBackoff() {
        Waybill waybill = pendingCancellation();
        WaybillShipment shipment = shipment(waybill, ShipmentStatus.CONFIRMED);
        stubLockedWaybill(waybill, shipment);
        when(shippingService.cancelProviderShipment(waybill.getId()))
                .thenThrow(new IllegalStateException("provider unavailable"));
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);

        service.request(waybill.getPublicId(), "cancel-key", null);

        assertThat(waybill.getStatus()).isEqualTo(WaybillStatus.CANCELLATION_PENDING);
        assertThat(shipment.getCancellationAttempt()).isEqualTo(1);
        assertThat(shipment.getCancellationFailureReason())
                .isEqualTo("Carrier cancellation request failed");
        assertThat(shipment.getNextCancellationAttemptAt())
                .isAfterOrEqualTo(before.plusMinutes(5));
    }

    @Test
    void maxAttemptFailureMovesCancellationToManualReview() {
        Waybill waybill = pendingCancellation();
        WaybillShipment shipment = shipment(waybill, ShipmentStatus.CONFIRMED);
        stubLockedWaybill(waybill, shipment);
        ReflectionTestUtils.setField(service, "maxCancellationAttempts", 1);
        when(shippingService.cancelProviderShipment(waybill.getId()))
                .thenThrow(new IllegalStateException("provider unavailable"));

        service.request(waybill.getPublicId(), "cancel-key", null);

        assertThat(waybill.getStatus()).isEqualTo(WaybillStatus.MANUAL_REVIEW);
        assertThat(shipment.getCancellationAttempt()).isEqualTo(1);
        assertThat(shipment.getNextCancellationAttemptAt()).isNull();
        assertThat(shipment.getCancellationFailureReason()).contains("retry limit reached");
    }

    @Test
    void carrierCancelledListenerFinalizesAndQueuesFullRefundOnce() {
        Waybill waybill = pendingCancellation();
        waybill.setPaymentStatus(WaybillPaymentStatus.CONFIRMED);
        waybill.setFundingPaymentReference("payment-ref");
        waybill.setCancellationReason("Customer request");
        WaybillShipment shipment = shipment(waybill, ShipmentStatus.CANCELLED);
        shipment.setCancellationAttempt(2);
        shipment.setNextCancellationAttemptAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10));
        shipment.setCancellationFailureReason("Earlier provider timeout");
        WaybillPaymentAttempt payment = paymentAttempt(waybill, WaybillPaymentAttemptStatus.SUCCESS);
        payment.setProviderTransactionId("txn-1");
        when(waybillRepository.findByIdForUpdate(waybill.getId())).thenReturn(Optional.of(waybill));
        when(shipmentRepository.findByWaybillIdForUpdate(waybill.getId()))
                .thenReturn(Optional.of(shipment));
        when(attemptRepository.findByReferenceForUpdate(payment.getReference()))
                .thenReturn(Optional.of(payment));
        when(refundRepository.findByPaymentAttempt_Id(payment.getId())).thenReturn(Optional.empty());
        WaybillRefundResponse queuedRefund = mock(WaybillRefundResponse.class);
        when(refundService.queueFullRefund(payment, payment.getAmount(), "Customer request", true))
                .thenAnswer(invocation -> {
                    waybill.setStatus(WaybillStatus.REFUND_PENDING);
                    return queuedRefund;
                });

        service.finalizeCarrierCancellation(waybill.getId());
        service.finalizeCarrierCancellation(waybill.getId());

        verify(refundService, times(1)).queueFullRefund(
                payment,
                payment.getAmount(),
                "Customer request",
                true
        );
        assertThat(waybill.getStatus()).isEqualTo(WaybillStatus.REFUND_PENDING);
        assertThat(shipment.getNextCancellationAttemptAt()).isNull();
        assertThat(shipment.getCancellationFailureReason()).isNull();
    }

    @Test
    void overlappingSameKeyRetryDoesNotLaunchConcurrentProviderCall() throws Exception {
        Waybill waybill = pendingCancellation();
        WaybillShipment shipment = shipment(waybill, ShipmentStatus.CONFIRMED);
        stubLockedWaybill(waybill, shipment);
        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        when(shippingService.cancelProviderShipment(waybill.getId())).thenAnswer(invocation -> {
            providerStarted.countDown();
            if (!releaseProvider.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test provider was not released");
            }
            return response(shipment, ShipmentStatus.PENDING);
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() ->
                    service.request(waybill.getPublicId(), "cancel-key", null)
            );
            assertThat(providerStarted.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> retry = executor.submit(() ->
                    service.request(waybill.getPublicId(), "cancel-key", null)
            );
            retry.get(5, TimeUnit.SECONDS);
            releaseProvider.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally {
            releaseProvider.countDown();
            executor.shutdownNow();
        }

        verify(shippingService, times(1)).cancelProviderShipment(waybill.getId());
        assertThat(shipment.getCancellationAttempt()).isEqualTo(1);
    }

    @Test
    void terminalWebhookStateWinsRaceWithProviderCancellationResponse() {
        Waybill waybill = pendingCancellation();
        WaybillShipment shipment = shipment(waybill, ShipmentStatus.CONFIRMED);
        stubLockedWaybill(waybill, shipment);
        when(shippingService.cancelProviderShipment(waybill.getId())).thenAnswer(invocation -> {
            shipment.setStatus(ShipmentStatus.DELIVERED);
            waybill.setStatus(WaybillStatus.MANUAL_REVIEW);
            return response(shipment, ShipmentStatus.CANCELLED);
        });

        service.request(waybill.getPublicId(), "cancel-key", null);

        assertThat(waybill.getStatus()).isEqualTo(WaybillStatus.MANUAL_REVIEW);
        assertThat(shipment.getNextCancellationAttemptAt()).isNull();
        verify(refundService, never()).queueFullRefund(any(), any(), any(), anyBoolean());
        verify(statusService, never()).transition(
                eq(waybill),
                eq(WaybillStatus.CANCELLED),
                any()
        );
    }

    @Test
    void sameKeyRetriesCarrierCancellationWhileShipmentRemainsCancellable() {
        Waybill waybill = pendingCancellation();
        WaybillShipment shipment = shipment(waybill, ShipmentStatus.CONFIRMED);
        when(waybillRepository.findByPublicIdForUpdate(waybill.getPublicId()))
                .thenReturn(Optional.of(waybill));
        when(waybillRepository.findByIdForUpdate(waybill.getId())).thenReturn(Optional.of(waybill));
        when(shipmentRepository.findByWaybillIdForUpdate(waybill.getId()))
                .thenReturn(Optional.of(shipment));
        when(shippingService.cancelProviderShipment(waybill.getId())).thenReturn(
                new ShipmentResponse(
                        shipment.getProviderShipmentId(),
                        ShipmentStatus.PENDING,
                        null,
                        null,
                        Instant.now()
                )
        );

        service.request(waybill.getPublicId(), "cancel-key", null);

        verify(shippingService).cancelProviderShipment(waybill.getId());
        assertThat(waybill.getStatus()).isEqualTo(WaybillStatus.CANCELLATION_PENDING);
    }

    @Test
    void unreconciledSuccessReceiptPreventsCancelledState() {
        Waybill waybill = pendingCancellation();
        WaybillPaymentAttempt attempt = paymentAttempt(waybill, WaybillPaymentAttemptStatus.INITIALIZED);
        WaybillPaymentReceipt receipt = successReceipt(waybill, attempt);
        stubImmediateFinalization(waybill);
        when(receiptRepository.findByWaybill_IdAndProviderStatusOrderByCreatedAtAsc(
                waybill.getId(),
                PaymentTransactionStatus.SUCCESS
        )).thenReturn(List.of(receipt));
        when(attemptRepository.findByIdForUpdate(attempt.getId())).thenReturn(Optional.of(attempt));

        service.request(waybill.getPublicId(), "cancel-key", null);

        assertThat(waybill.getStatus()).isEqualTo(WaybillStatus.MANUAL_REVIEW);
        assertThat(waybill.getPaymentStatus()).isEqualTo(WaybillPaymentStatus.REVIEW);
        verify(statusService, never()).transition(
                eq(waybill),
                eq(WaybillStatus.CANCELLED),
                any()
        );
    }

    @Test
    void reconciledSuccessReceiptQueuesRefundInsteadOfCancelling() {
        Waybill waybill = pendingCancellation();
        WaybillPaymentAttempt attempt = paymentAttempt(waybill, WaybillPaymentAttemptStatus.SUCCESS);
        attempt.setProviderTransactionId("txn-1");
        WaybillPaymentReceipt receipt = successReceipt(waybill, attempt);
        WaybillRefundResponse queuedRefund = mock(WaybillRefundResponse.class);
        stubImmediateFinalization(waybill);
        when(receiptRepository.findByWaybill_IdAndProviderStatusOrderByCreatedAtAsc(
                waybill.getId(),
                PaymentTransactionStatus.SUCCESS
        )).thenReturn(List.of(receipt));
        when(attemptRepository.findByIdForUpdate(attempt.getId())).thenReturn(Optional.of(attempt));
        when(refundRepository.findByPaymentAttempt_Id(attempt.getId())).thenReturn(Optional.empty());
        when(refundService.queueFullRefund(any(), any(), any(), anyBoolean()))
                .thenReturn(queuedRefund);

        service.request(waybill.getPublicId(), "cancel-key", "Customer request");

        verify(refundService).queueFullRefund(
                eq(attempt),
                eq(new BigDecimal("100.00")),
                eq(null),
                eq(true)
        );
        verify(statusService, never()).transition(
                eq(waybill),
                eq(WaybillStatus.CANCELLED),
                any()
        );
    }

    private void stubImmediateFinalization(Waybill waybill) {
        when(waybillRepository.findByPublicIdForUpdate(waybill.getPublicId()))
                .thenReturn(Optional.of(waybill));
        when(waybillRepository.findByIdForUpdate(waybill.getId())).thenReturn(Optional.of(waybill));
        when(shipmentRepository.findByWaybillIdForUpdate(waybill.getId()))
                .thenReturn(Optional.empty());
    }

    private void stubLockedWaybill(Waybill waybill, WaybillShipment shipment) {
        when(waybillRepository.findByPublicIdForUpdate(waybill.getPublicId()))
                .thenReturn(Optional.of(waybill));
        when(waybillRepository.findByIdForUpdate(waybill.getId())).thenReturn(Optional.of(waybill));
        when(shipmentRepository.findByWaybillIdForUpdate(waybill.getId()))
                .thenReturn(Optional.ofNullable(shipment));
    }

    private void stubScheduledWaybill(Waybill waybill, WaybillShipment shipment) {
        when(waybillRepository.findWaybillIdsDueForCancellation(
                eq(WaybillStatus.CANCELLATION_PENDING),
                anyCollection(),
                any(LocalDateTime.class),
                any(Pageable.class)
        )).thenReturn(List.of(waybill.getId()));
        when(waybillRepository.findByIdForUpdate(waybill.getId())).thenReturn(Optional.of(waybill));
        when(shipmentRepository.findByWaybillIdForUpdate(waybill.getId()))
                .thenReturn(Optional.ofNullable(shipment));
    }

    private Waybill cancellableWaybill() {
        Waybill waybill = pendingCancellation();
        waybill.setStatus(WaybillStatus.READY_FOR_DELIVERY);
        waybill.setPaymentStatus(WaybillPaymentStatus.CONFIRMED);
        waybill.setCancellationIdempotencyKey(null);
        waybill.setCancellationRequestedAt(null);
        return waybill;
    }

    private ShipmentResponse response(WaybillShipment shipment, ShipmentStatus status) {
        return new ShipmentResponse(
                shipment.getProviderShipmentId(),
                status,
                null,
                null,
                Instant.now()
        );
    }

    private Waybill pendingCancellation() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return Waybill.builder()
                .id(10L)
                .publicId("waybill-10")
                .creator(creator)
                .currency("NGN")
                .status(WaybillStatus.CANCELLATION_PENDING)
                .paymentStatus(WaybillPaymentStatus.PENDING)
                .cancellationIdempotencyKey("cancel-key")
                .cancellationRequestedAt(now.minusMinutes(1))
                .createdAt(now.minusHours(1))
                .updatedAt(now.minusMinutes(1))
                .build();
    }

    private WaybillShipment shipment(Waybill waybill, ShipmentStatus status) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return WaybillShipment.builder()
                .id(20L)
                .waybill(waybill)
                .provider("TERMINAL_AFRICA")
                .providerShipmentId("SH-20")
                .status(status)
                .createdAt(now.minusHours(1))
                .updatedAt(now.minusMinutes(1))
                .build();
    }

    private WaybillPaymentAttempt paymentAttempt(
            Waybill waybill,
            WaybillPaymentAttemptStatus status
    ) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return WaybillPaymentAttempt.builder()
                .id(30L)
                .waybill(waybill)
                .reference("payment-ref")
                .amount(new BigDecimal("100.00"))
                .currency("NGN")
                .status(status)
                .createdAt(now.minusMinutes(5))
                .updatedAt(now.minusMinutes(5))
                .build();
    }

    private WaybillPaymentReceipt successReceipt(
            Waybill waybill,
            WaybillPaymentAttempt attempt
    ) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return WaybillPaymentReceipt.builder()
                .id(40L)
                .waybill(waybill)
                .attempt(attempt)
                .providerEventKey("txn-1")
                .reference(attempt.getReference())
                .amount(new BigDecimal("100.00"))
                .currency("NGN")
                .providerStatus(PaymentTransactionStatus.SUCCESS)
                .classification(WaybillPaymentClassification.FAILED)
                .createdAt(now.minusMinutes(1))
                .build();
    }
}
