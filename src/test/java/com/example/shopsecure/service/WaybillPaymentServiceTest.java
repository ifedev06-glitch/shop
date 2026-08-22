package com.example.shopsecure.service;

import com.example.shopsecure.entity.ShippingQuoteStatus;
import com.example.shopsecure.entity.Waybill;
import com.example.shopsecure.entity.WaybillPaymentAttempt;
import com.example.shopsecure.entity.WaybillPaymentAttemptStatus;
import com.example.shopsecure.entity.WaybillPaymentClassification;
import com.example.shopsecure.entity.WaybillPaymentReceipt;
import com.example.shopsecure.entity.WaybillPaymentStatus;
import com.example.shopsecure.entity.WaybillShipment;
import com.example.shopsecure.entity.WaybillShippingQuote;
import com.example.shopsecure.entity.WaybillStatus;
import com.example.shopsecure.provider.payment.PaymentStatusUpdatedEvent;
import com.example.shopsecure.provider.payment.PaymentTransactionStatus;
import com.example.shopsecure.provider.payment.PaymentVerificationResult;
import com.example.shopsecure.repository.WaybillPaymentAttemptRepository;
import com.example.shopsecure.repository.WaybillPaymentReceiptRepository;
import com.example.shopsecure.repository.WaybillRefundRepository;
import com.example.shopsecure.repository.WaybillRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WaybillPaymentServiceTest {

    private WaybillRepository waybillRepository;
    private WaybillPaymentAttemptRepository attemptRepository;
    private WaybillPaymentReceiptRepository receiptRepository;
    private WaybillRefundRepository refundRepository;
    private WaybillShippingQuoteRepository quoteRepository;
    private WaybillRefundService refundService;
    private WaybillStatusService statusService;
    private WaybillPaymentService service;

    @BeforeEach
    void setUp() {
        waybillRepository = mock(WaybillRepository.class);
        attemptRepository = mock(WaybillPaymentAttemptRepository.class);
        receiptRepository = mock(WaybillPaymentReceiptRepository.class);
        refundRepository = mock(WaybillRefundRepository.class);
        quoteRepository = mock(WaybillShippingQuoteRepository.class);
        refundService = mock(WaybillRefundService.class);
        statusService = mock(WaybillStatusService.class);
        service = new WaybillPaymentService(
                waybillRepository,
                attemptRepository,
                receiptRepository,
                refundRepository,
                quoteRepository,
                mock(WaybillAccessService.class),
                refundService,
                statusService,
                mock(ApplicationEventPublisher.class)
        );
        ReflectionTestUtils.setField(service, "recheckDelay", Duration.ofMinutes(5));
        doAnswer(invocation -> {
            Waybill waybill = invocation.getArgument(0);
            waybill.setStatus(invocation.getArgument(1));
            return true;
        }).when(statusService).transition(any(Waybill.class), any(WaybillStatus.class), any());
    }

    @Test
    void providerVerificationReassessesAndUpdatesFailedSuccessReceipt() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Waybill waybill = waybill(WaybillStatus.AWAITING_PAYMENT, WaybillPaymentStatus.PENDING);
        waybill.setPaymentDueAt(now.plusMinutes(10));
        WaybillPaymentAttempt attempt = attempt(waybill, WaybillPaymentAttemptStatus.INITIALIZED);
        WaybillShippingQuote quote = quote(waybill, now.plusMinutes(10));
        WaybillPaymentReceipt receipt = WaybillPaymentReceipt.builder()
                .id(50L)
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
        stubLocked(waybill, attempt);
        when(receiptRepository.findByProviderEventKey("txn-1")).thenReturn(Optional.of(receipt));
        when(quoteRepository.findByWaybillIdAndStatusForUpdate(
                waybill.getId(),
                ShippingQuoteStatus.SELECTED
        )).thenReturn(Optional.of(quote));

        service.handleVerification(new PaymentVerificationResult(
                "txn-1",
                attempt.getReference(),
                PaymentTransactionStatus.SUCCESS,
                new BigDecimal("100.00"),
                "NGN",
                "card",
                now.toInstant(ZoneOffset.UTC)
        ));

        assertThat(attempt.getStatus()).isEqualTo(WaybillPaymentAttemptStatus.SUCCESS);
        assertThat(receipt.getClassification()).isEqualTo(WaybillPaymentClassification.MATCHED);
        assertThat(waybill.getPaymentStatus()).isEqualTo(WaybillPaymentStatus.CONFIRMED);
        assertThat(waybill.getStatus()).isEqualTo(WaybillStatus.SHIPMENT_BOOKING);
        verify(receiptRepository).save(receipt);
        verify(refundService, never()).queueFullRefund(any(), any(), any(), anyBoolean());
    }

    @Test
    void capturedSuccessWithInvalidFrozenStateBecomesSuccessAndQueuesFullRefund() {
        Waybill waybill = waybill(WaybillStatus.AWAITING_PAYMENT, WaybillPaymentStatus.PENDING);
        WaybillPaymentAttempt attempt = attempt(waybill, WaybillPaymentAttemptStatus.INITIALIZED);
        stubLocked(waybill, attempt);
        when(receiptRepository.findByProviderEventKey("txn-2")).thenReturn(Optional.empty());
        when(refundRepository.findByPaymentAttempt_Id(attempt.getId())).thenReturn(Optional.empty());
        when(quoteRepository.findByWaybillIdAndStatusForUpdate(any(), any()))
                .thenReturn(Optional.empty());

        service.handleEvent(successEvent(attempt, "txn-2"));

        assertThat(attempt.getStatus()).isEqualTo(WaybillPaymentAttemptStatus.SUCCESS);
        ArgumentCaptor<WaybillPaymentReceipt> receipt = ArgumentCaptor.forClass(
                WaybillPaymentReceipt.class
        );
        verify(receiptRepository).save(receipt.capture());
        assertThat(receipt.getValue().getProviderStatus()).isEqualTo(PaymentTransactionStatus.SUCCESS);
        assertThat(receipt.getValue().getClassification()).isEqualTo(WaybillPaymentClassification.FAILED);
        verify(refundService).queueFullRefund(
                eq(attempt),
                eq(new BigDecimal("100.00")),
                eq("Full refund of waybill checkout"),
                eq(true)
        );
    }

    @Test
    void supersededSuccessIsLateBeforeLookingAtCurrentFrozenQuote() {
        Waybill waybill = waybill(WaybillStatus.AWAITING_QUOTE, WaybillPaymentStatus.NOT_STARTED);
        waybill.setTotalAmount(null);
        WaybillPaymentAttempt attempt = attempt(waybill, WaybillPaymentAttemptStatus.SUPERSEDED);
        stubLocked(waybill, attempt);
        when(receiptRepository.findByProviderEventKey("txn-3")).thenReturn(Optional.empty());
        when(refundRepository.findByPaymentAttempt_Id(attempt.getId())).thenReturn(Optional.empty());

        service.handleEvent(successEvent(attempt, "txn-3"));

        ArgumentCaptor<WaybillPaymentReceipt> receipt = ArgumentCaptor.forClass(
                WaybillPaymentReceipt.class
        );
        verify(receiptRepository).save(receipt.capture());
        assertThat(receipt.getValue().getClassification()).isEqualTo(WaybillPaymentClassification.LATE);
        verify(quoteRepository, never()).findByWaybillIdAndStatusForUpdate(any(), any());
        verify(refundService).queueFullRefund(
                eq(attempt),
                eq(new BigDecimal("100.00")),
                eq("Full refund of late waybill checkout"),
                eq(true)
        );
    }

    private void stubLocked(Waybill waybill, WaybillPaymentAttempt attempt) {
        when(attemptRepository.findByReference(attempt.getReference())).thenReturn(Optional.of(attempt));
        when(waybillRepository.findByIdForUpdate(waybill.getId())).thenReturn(Optional.of(waybill));
        when(attemptRepository.findByIdForUpdate(attempt.getId())).thenReturn(Optional.of(attempt));
    }

    private PaymentStatusUpdatedEvent successEvent(WaybillPaymentAttempt attempt, String eventId) {
        return new PaymentStatusUpdatedEvent(
                eventId,
                attempt.getReference(),
                PaymentTransactionStatus.SUCCESS,
                new BigDecimal("100.00"),
                "NGN",
                "card",
                null,
                Instant.now(),
                true
        );
    }

    private Waybill waybill(WaybillStatus status, WaybillPaymentStatus paymentStatus) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return Waybill.builder()
                .id(1L)
                .publicId("waybill-1")
                .currency("NGN")
                .status(status)
                .paymentStatus(paymentStatus)
                .totalAmount(new BigDecimal("100.00"))
                .createdAt(now.minusHours(1))
                .updatedAt(now.minusHours(1))
                .build();
    }

    private WaybillPaymentAttempt attempt(
            Waybill waybill,
            WaybillPaymentAttemptStatus status
    ) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return WaybillPaymentAttempt.builder()
                .id(10L)
                .waybill(waybill)
                .reference("waybill-ref-1")
                .amount(new BigDecimal("100.00"))
                .currency("NGN")
                .status(status)
                .createdAt(now.minusMinutes(5))
                .updatedAt(now.minusMinutes(5))
                .build();
    }

    private WaybillShippingQuote quote(Waybill waybill, LocalDateTime expiresAt) {
        WaybillShipment shipment = WaybillShipment.builder()
                .id(20L)
                .waybill(waybill)
                .build();
        return WaybillShippingQuote.builder()
                .id(30L)
                .shipment(shipment)
                .totalAmount(new BigDecimal("100.00"))
                .currency("NGN")
                .status(ShippingQuoteStatus.SELECTED)
                .expiresAt(expiresAt)
                .build();
    }
}
