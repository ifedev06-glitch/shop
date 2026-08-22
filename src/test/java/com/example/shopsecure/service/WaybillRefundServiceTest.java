package com.example.shopsecure.service;

import com.example.shopsecure.entity.Waybill;
import com.example.shopsecure.entity.WaybillPaymentAttempt;
import com.example.shopsecure.entity.WaybillPaymentAttemptStatus;
import com.example.shopsecure.entity.WaybillPaymentStatus;
import com.example.shopsecure.entity.WaybillRefund;
import com.example.shopsecure.entity.WaybillRefundStatus;
import com.example.shopsecure.entity.WaybillStatus;
import com.example.shopsecure.provider.payment.RefundProvider;
import com.example.shopsecure.repository.WaybillPaymentAttemptRepository;
import com.example.shopsecure.repository.WaybillRefundRepository;
import com.example.shopsecure.repository.WaybillRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WaybillRefundServiceTest {

    private RefundProvider refundProvider;
    private WaybillRepository waybillRepository;
    private WaybillPaymentAttemptRepository attemptRepository;
    private WaybillRefundRepository refundRepository;
    private WaybillStatusService statusService;
    private WaybillRefundService service;

    @BeforeEach
    void setUp() {
        refundProvider = mock(RefundProvider.class);
        waybillRepository = mock(WaybillRepository.class);
        attemptRepository = mock(WaybillPaymentAttemptRepository.class);
        refundRepository = mock(WaybillRefundRepository.class);
        statusService = mock(WaybillStatusService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        service = new WaybillRefundService(
                refundProvider,
                waybillRepository,
                attemptRepository,
                refundRepository,
                statusService,
                mock(ApplicationEventPublisher.class),
                transactionManager
        );
        ReflectionTestUtils.setField(service, "reconciliationBatchSize", 25);
        ReflectionTestUtils.setField(service, "reconciliationRetryDelay", Duration.ofMinutes(5));
        ReflectionTestUtils.setField(service, "unknownOutcomeTimeout", Duration.ofHours(1));
        doAnswer(invocation -> {
            Waybill waybill = invocation.getArgument(0);
            waybill.setStatus(invocation.getArgument(1));
            return true;
        }).when(statusService).transition(any(Waybill.class), any(WaybillStatus.class), anyString());
    }

    @Test
    void dispatchUnknownWithoutProviderIdPastTimeoutMovesRefundAndRootToManualReview() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Waybill waybill = Waybill.builder()
                .id(1L)
                .publicId("waybill-refund-timeout")
                .currency("NGN")
                .status(WaybillStatus.REFUND_PENDING)
                .paymentStatus(WaybillPaymentStatus.REFUND_PENDING)
                .fundingPaymentReference("waybill-payment-reference")
                .createdAt(now.minusHours(3))
                .updatedAt(now.minusHours(2))
                .build();
        WaybillPaymentAttempt attempt = WaybillPaymentAttempt.builder()
                .id(2L)
                .waybill(waybill)
                .reference("waybill-payment-reference")
                .amount(new BigDecimal("5500.00"))
                .currency("NGN")
                .status(WaybillPaymentAttemptStatus.SUCCESS)
                .createdAt(now.minusHours(3))
                .updatedAt(now.minusHours(2))
                .build();
        WaybillRefund refund = WaybillRefund.builder()
                .id(3L)
                .waybill(waybill)
                .paymentAttempt(attempt)
                .amount(new BigDecimal("5500.00"))
                .currency("NGN")
                .status(WaybillRefundStatus.DISPATCH_UNKNOWN)
                .reason("Full refund")
                .createdAt(now.minusHours(2))
                .updatedAt(now.minusMinutes(10))
                .build();

        when(refundRepository.findIdsDueForReconciliation(any(), any(), any()))
                .thenReturn(List.of(refund.getId()));
        when(refundRepository.findById(refund.getId())).thenReturn(Optional.of(refund));
        when(waybillRepository.findByIdForUpdate(waybill.getId())).thenReturn(Optional.of(waybill));
        when(attemptRepository.findByIdForUpdate(attempt.getId())).thenReturn(Optional.of(attempt));
        when(refundRepository.findByIdForUpdate(refund.getId())).thenReturn(Optional.of(refund));

        service.reconcileRefunds();

        assertThat(refund.getStatus()).isEqualTo(WaybillRefundStatus.MANUAL_REVIEW);
        assertThat(refund.getFailureReason())
                .isEqualTo("Refund dispatch outcome requires manual provider review");
        assertThat(waybill.getStatus()).isEqualTo(WaybillStatus.MANUAL_REVIEW);
        assertThat(waybill.getPaymentStatus()).isEqualTo(WaybillPaymentStatus.REVIEW);
        verify(statusService).transition(
                waybill,
                WaybillStatus.MANUAL_REVIEW,
                "Waybill refund dispatch outcome requires manual provider review"
        );
        verify(refundRepository).save(refund);
        verify(waybillRepository).save(waybill);
        verify(refundProvider, never()).initiateRefund(any());
        verify(refundProvider, never()).verifyRefund(anyString());
    }
}
