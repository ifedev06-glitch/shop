package com.example.shopsecure.service;

import com.example.shopsecure.entity.User;
import com.example.shopsecure.entity.Waybill;
import com.example.shopsecure.entity.WaybillInvitation;
import com.example.shopsecure.entity.WaybillPayerType;
import com.example.shopsecure.entity.WaybillPaymentAttempt;
import com.example.shopsecure.entity.WaybillPaymentAttemptStatus;
import com.example.shopsecure.entity.WaybillPaymentStatus;
import com.example.shopsecure.entity.WaybillStatus;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.provider.payment.PaymentProvider;
import com.example.shopsecure.repository.WaybillInvitationRepository;
import com.example.shopsecure.repository.WaybillPaymentAttemptRepository;
import com.example.shopsecure.repository.WaybillRepository;
import com.example.shopsecure.repository.WaybillShippingQuoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WaybillCheckoutServiceTest {

    private WaybillRepository waybillRepository;
    private WaybillInvitationRepository invitationRepository;
    private WaybillPaymentAttemptRepository attemptRepository;
    private WaybillAccessService accessService;
    private OrderService orderService;
    private PaymentProvider paymentProvider;
    private WaybillCheckoutService service;
    private User creator;

    @BeforeEach
    void setUp() {
        waybillRepository = mock(WaybillRepository.class);
        invitationRepository = mock(WaybillInvitationRepository.class);
        attemptRepository = mock(WaybillPaymentAttemptRepository.class);
        accessService = mock(WaybillAccessService.class);
        orderService = mock(OrderService.class);
        paymentProvider = mock(PaymentProvider.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        service = new WaybillCheckoutService(
                waybillRepository,
                invitationRepository,
                mock(WaybillShippingQuoteRepository.class),
                attemptRepository,
                accessService,
                orderService,
                paymentProvider,
                transactionManager
        );
        ReflectionTestUtils.setField(service, "appBaseUrl", "https://shop.test");
        ReflectionTestUtils.setField(service, "recheckDelay", Duration.ofMinutes(5));
        creator = User.builder().id(1L).email("creator@example.com").build();
        when(orderService.currentUser()).thenReturn(creator);
    }

    @Test
    void differentIdempotencyKeyCannotReplayAnotherOpenAttempt() {
        Waybill waybill = waybill(WaybillPayerType.CREATOR);
        WaybillPaymentAttempt open = attempt(
                waybill,
                WaybillPayerType.CREATOR,
                null,
                "first-key"
        );
        when(waybillRepository.findByPublicIdForUpdate(waybill.getPublicId()))
                .thenReturn(Optional.of(waybill));
        when(attemptRepository.findByWaybillIdAndIdempotencyKeyForUpdate(
                waybill.getId(),
                "different-key"
        )).thenReturn(Optional.empty());
        when(attemptRepository.findFirstByWaybill_IdAndStatusInOrderByCreatedAtDesc(any(), any()))
                .thenReturn(Optional.of(open));

        assertThatThrownBy(() -> service.initializeCreator(waybill.getPublicId(), "different-key"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Another waybill payment attempt is open; retry with its Idempotency-Key");
        verify(paymentProvider, never()).initializeTransaction(any());
    }

    @Test
    void invitedReplayMustBelongToExactInvitation() {
        Waybill waybill = waybill(WaybillPayerType.INVITED_PAYER);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        WaybillInvitation current = invitation(1L, waybill, now);
        WaybillInvitation different = invitation(2L, waybill, now);
        WaybillPaymentAttempt replay = attempt(
                waybill,
                WaybillPayerType.INVITED_PAYER,
                different,
                "same-key"
        );
        when(accessService.requirePayAccess("raw-invitation", "access-token"))
                .thenReturn(current);
        when(waybillRepository.findByIdForUpdate(waybill.getId())).thenReturn(Optional.of(waybill));
        when(invitationRepository.findById(current.getId())).thenReturn(Optional.of(current));
        when(attemptRepository.findByWaybillIdAndIdempotencyKeyForUpdate(
                waybill.getId(),
                "same-key"
        )).thenReturn(Optional.of(replay));

        assertThatThrownBy(() -> service.initializeInvited(
                "raw-invitation",
                "access-token",
                "same-key"
        )).isInstanceOf(AccessDeniedException.class)
                .hasMessage("Waybill payment attempt belongs to a different invitation");
        verify(paymentProvider, never()).initializeTransaction(any());
    }

    private Waybill waybill(WaybillPayerType payerType) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return Waybill.builder()
                .id(10L)
                .publicId("waybill-10")
                .creator(creator)
                .payerType(payerType)
                .currency("NGN")
                .status(WaybillStatus.AWAITING_PAYMENT)
                .paymentStatus(WaybillPaymentStatus.PENDING)
                .totalAmount(new BigDecimal("100.00"))
                .paymentDueAt(now.plusMinutes(10))
                .createdAt(now.minusHours(1))
                .updatedAt(now.minusHours(1))
                .build();
    }

    private WaybillInvitation invitation(Long id, Waybill waybill, LocalDateTime now) {
        return WaybillInvitation.builder()
                .id(id)
                .waybill(waybill)
                .canPay(true)
                .stagedEmail("payer@example.com")
                .verifiedAt(now.minusMinutes(1))
                .expiresAt(now.plusHours(1))
                .createdAt(now.minusHours(1))
                .updatedAt(now.minusHours(1))
                .build();
    }

    private WaybillPaymentAttempt attempt(
            Waybill waybill,
            WaybillPayerType payerType,
            WaybillInvitation invitation,
            String idempotencyKey
    ) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return WaybillPaymentAttempt.builder()
                .id(20L)
                .waybill(waybill)
                .invitation(invitation)
                .payerType(payerType)
                .reference("waybill-reference")
                .idempotencyKey(idempotencyKey)
                .customerEmail("payer@example.com")
                .amount(new BigDecimal("100.00"))
                .currency("NGN")
                .status(WaybillPaymentAttemptStatus.INITIALIZED)
                .createdAt(now.minusMinutes(5))
                .updatedAt(now.minusMinutes(5))
                .build();
    }
}
