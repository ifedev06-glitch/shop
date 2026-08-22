package com.example.shopsecure.service;

import com.example.shopsecure.event.OrderFundedEvent;
import com.example.shopsecure.entity.OrderStatus;
import com.example.shopsecure.provider.shipping.ShipmentStatus;
import com.example.shopsecure.provider.shipping.ShippingProviderCode;
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
import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderFundedListener {

    private final OrderShippingService shippingService;
    private final OrderShipmentRepository shipmentRepository;
    private final AtomicLong chowdeckTrackingCursor = new AtomicLong();
    private final AtomicLong chowdeckTrackingHighWater = new AtomicLong();

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
        List<ShipmentStatus> shipmentStatuses = List.of(
                ShipmentStatus.PENDING,
                ShipmentStatus.CONFIRMED,
                ShipmentStatus.IN_TRANSIT
        );
        List<OrderStatus> orderStatuses = List.of(
                OrderStatus.SHIPMENT_BOOKING,
                OrderStatus.READY_FOR_DELIVERY,
                OrderStatus.SHIPPING_FAILED,
                OrderStatus.PAYOUT_PENDING,
                OrderStatus.PAYOUT_FAILED,
                OrderStatus.IN_TRANSIT,
                OrderStatus.COMPLETED
        );
        long highWater = chowdeckTrackingHighWater.get();
        if (highWater == 0) {
            Long currentHighWater = shipmentRepository.findProviderTrackingHighWaterOrderId(
                    ShippingProviderCode.CHOWDECK,
                    shipmentStatuses,
                    orderStatuses
            );
            if (currentHighWater == null) {
                return;
            }
            highWater = currentHighWater;
            chowdeckTrackingHighWater.set(highWater);
            chowdeckTrackingCursor.set(0);
        }
        List<Long> trackingOrderIds = shipmentRepository.findOrderIdsForProviderTracking(
                ShippingProviderCode.CHOWDECK,
                shipmentStatuses,
                orderStatuses,
                chowdeckTrackingCursor.get(),
                highWater,
                PageRequest.of(0, Math.max(1, reconciliationBatchSize))
        );
        if (trackingOrderIds.isEmpty()) {
            resetChowdeckTrackingCycle();
            return;
        }
        trackingOrderIds.forEach(orderId -> {
            try {
                shippingService.reconcileChowdeckTracking(orderId);
            } catch (RuntimeException exception) {
                log.error("Unable to refresh Chowdeck shipment: orderId={}", orderId);
            } finally {
                chowdeckTrackingCursor.set(orderId);
            }
        });
        if (chowdeckTrackingCursor.get() >= highWater) {
            resetChowdeckTrackingCycle();
        }
    }

    private void resetChowdeckTrackingCycle() {
        chowdeckTrackingCursor.set(0);
        chowdeckTrackingHighWater.set(0);
    }
}
