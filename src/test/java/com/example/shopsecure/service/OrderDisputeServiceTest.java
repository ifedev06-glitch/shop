package com.example.shopsecure.service;

import com.example.shopsecure.entity.*;
import com.example.shopsecure.event.OrderDisputeOpenedEvent;
import com.example.shopsecure.repository.OrderCounterpartyRepository;
import com.example.shopsecure.repository.OrderDisputeRepository;
import com.example.shopsecure.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrderDisputeServiceTest {

    private OrderRepository orderRepository;
    private OrderCounterpartyRepository counterpartyRepository;
    private OrderDisputeRepository disputeRepository;
    private OrderDisputeNotificationService notificationService;
    private ApplicationEventPublisher eventPublisher;
    private OrderDisputeService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        counterpartyRepository = mock(OrderCounterpartyRepository.class);
        disputeRepository = mock(OrderDisputeRepository.class);
        notificationService = mock(OrderDisputeNotificationService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new OrderDisputeService(
                orderRepository,
                counterpartyRepository,
                disputeRepository,
                notificationService,
                eventPublisher
        );
    }

    @Test
    void persistsOneFundedDisputeWithoutChangingOrderLifecycle() {
        Order order = Order.builder()
                .id(42L)
                .status(OrderStatus.IN_TRANSIT)
                .paymentStatus(PaymentStatus.CONFIRMED)
                .settlementStatus(SettlementStatus.PENDING)
                .fundedAt(Instant.now())
                .build();
        OrderCounterparty counterparty = OrderCounterparty.builder().order(order).build();
        when(orderRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(order));
        when(counterpartyRepository.findByOrder_Id(42L)).thenReturn(Optional.of(counterparty));
        when(disputeRepository.save(any())).thenAnswer(invocation -> {
            OrderDispute dispute = invocation.getArgument(0);
            dispute.setId(17L);
            return dispute;
        });

        service.submit(42L, "  Package arrived damaged.  ");

        ArgumentCaptor<OrderDispute> saved = ArgumentCaptor.forClass(OrderDispute.class);
        verify(disputeRepository).save(saved.capture());
        assertThat(saved.getValue().getReason()).isEqualTo("Package arrived damaged.");
        assertThat(saved.getValue().getStatus()).isEqualTo(OrderDisputeStatus.OPEN);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.IN_TRANSIT);
        assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(order.getSettlementStatus()).isEqualTo(SettlementStatus.PENDING);
        verify(notificationService).enqueueOpened(saved.getValue(), counterparty);
        verify(eventPublisher).publishEvent(new OrderDisputeOpenedEvent(17L));
    }

    @Test
    void silentlyIgnoresUnknownUnfundedAndDuplicateOrders() {
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());
        service.submit(1L, "Unknown order");

        Order unfunded = Order.builder().id(2L).paymentStatus(PaymentStatus.NOT_STARTED).build();
        when(orderRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(unfunded));
        service.submit(2L, "Unfunded order");

        Order missingFundingTimestamp = Order.builder()
                .id(3L)
                .paymentStatus(PaymentStatus.CONFIRMED)
                .build();
        when(orderRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(missingFundingTimestamp));
        service.submit(3L, "Missing funding timestamp");

        Order funded = Order.builder()
                .id(4L)
                .paymentStatus(PaymentStatus.CONFIRMED)
                .fundedAt(Instant.now())
                .build();
        when(orderRepository.findByIdForUpdate(4L)).thenReturn(Optional.of(funded));
        when(disputeRepository.existsByOrder_Id(4L)).thenReturn(true);
        service.submit(4L, "Duplicate order");

        verify(disputeRepository, never()).save(any());
        verifyNoInteractions(counterpartyRepository, notificationService, eventPublisher);
    }
}
