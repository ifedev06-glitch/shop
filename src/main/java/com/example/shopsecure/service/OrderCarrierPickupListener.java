package com.example.shopsecure.service;

import com.example.shopsecure.entity.Order;
import com.example.shopsecure.entity.OrderTrackingNotificationStatus;
import com.example.shopsecure.entity.OrderTrackingNotificationType;
import com.example.shopsecure.event.OrderCarrierPickupConfirmedEvent;
import com.example.shopsecure.repository.OrderRepository;
import com.example.shopsecure.repository.OrderTrackingNotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderCarrierPickupListener {

    private final OrderRepository orderRepository;
    private final OrderSettlementService settlementService;
    private final OrderTrackingNotificationService notificationService;
    private final OrderTrackingNotificationRepository notificationRepository;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void enqueuePickupEmails(OrderCarrierPickupConfirmedEvent event) {
        Order order = orderRepository.findById(event.orderId()).orElse(null);
        if (order != null) {
            notificationService.enqueueCarrierPickup(order);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void dispatchCarrierPickupPayout(OrderCarrierPickupConfirmedEvent event) {
        try {
            settlementService.dispatchCarrierPickupPayout(event.orderId());
        } catch (RuntimeException exception) {
            log.error("Unable to dispatch carrier-pickup payout: orderId={}", event.orderId(), exception);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void dispatchPickupEmails(OrderCarrierPickupConfirmedEvent event) {
        notificationRepository.findIdsByOrderAndTypeAndStatusDue(
                event.orderId(),
                OrderTrackingNotificationType.CARRIER_PICKUP_CONFIRMED,
                OrderTrackingNotificationStatus.PENDING,
                Instant.now()
        ).forEach(notificationService::dispatch);
    }
}
