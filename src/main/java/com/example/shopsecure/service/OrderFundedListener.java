package com.example.shopsecure.service;

import com.example.shopsecure.event.OrderFundedEvent;
import com.example.shopsecure.entity.OrderStatus;
import com.example.shopsecure.repository.OrderShipmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderFundedListener {

    private final OrderShippingService shippingService;
    private final OrderShipmentRepository shipmentRepository;

    @Value("${shipping.booking-reconciliation.batch-size:25}")
    private int reconciliationBatchSize;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void arrangeShipping(OrderFundedEvent event) {
        try {
            shippingService.arrangePickup(event.orderId());
        } catch (RuntimeException exception) {
            log.error("Unable to arrange shipping after funding: orderId={}", event.orderId());
        }
    }

    @Scheduled(
            initialDelayString = "${shipping.booking-reconciliation.initial-delay-ms:60000}",
            fixedDelayString = "${shipping.booking-reconciliation.delay-ms:60000}"
    )
    public void reconcileShippingBookings() {
        shipmentRepository.findOrderIdsReadyForBookingReconciliation(
                List.of(OrderStatus.SHIPMENT_BOOKING, OrderStatus.SHIPPING_FAILED),
                Instant.now(),
                PageRequest.of(0, Math.max(1, reconciliationBatchSize))
        ).forEach(orderId -> {
            try {
                shippingService.arrangePickup(orderId);
            } catch (RuntimeException exception) {
                log.error("Unable to reconcile shipment booking: orderId={}", orderId);
            }
        });
    }
}
