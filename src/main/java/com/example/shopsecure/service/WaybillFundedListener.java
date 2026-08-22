package com.example.shopsecure.service;

import com.example.shopsecure.entity.WaybillStatus;
import com.example.shopsecure.provider.shipping.ShipmentStatus;
import com.example.shopsecure.provider.shipping.ShippingProviderCode;
import com.example.shopsecure.event.WaybillFundedEvent;
import com.example.shopsecure.repository.WaybillShipmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
@Slf4j
public class WaybillFundedListener {

    private static final int MAX_RECONCILIATION_BATCH_SIZE = 100;

    private final WaybillShippingService shippingService;
    private final WaybillShipmentRepository shipmentRepository;
    private final AtomicLong chowdeckTrackingCursor = new AtomicLong();
    private final AtomicLong chowdeckTrackingHighWater = new AtomicLong();

    @Value("${waybill.booking-reconciliation.batch-size:25}")
    private int reconciliationBatchSize;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void arrangeShipping(WaybillFundedEvent event) {
        try {
            shippingService.arrangePickup(event.waybillId());
        } catch (RuntimeException exception) {
            log.error("Unable to arrange waybill shipping after funding: waybillId={}", event.waybillId());
        }
    }

    @Scheduled(
            initialDelayString = "${waybill.booking-reconciliation.initial-delay-ms:60000}",
            fixedDelayString = "${waybill.booking-reconciliation.delay-ms:60000}"
    )
    public void reconcileShippingBookings() {
        int batchSize = Math.min(
                MAX_RECONCILIATION_BATCH_SIZE,
                Math.max(1, reconciliationBatchSize)
        );
        shipmentRepository.findWaybillIdsDueForBooking(
                List.of(WaybillStatus.SHIPMENT_BOOKING, WaybillStatus.SHIPPING_FAILED),
                LocalDateTime.now(ZoneOffset.UTC),
                PageRequest.of(0, batchSize)
        ).forEach(waybillId -> {
            try {
                shippingService.arrangePickup(waybillId);
            } catch (RuntimeException exception) {
                log.error("Unable to reconcile waybill shipment booking: waybillId={}", waybillId);
            }
        });
        List<ShipmentStatus> shipmentStatuses = List.of(
                ShipmentStatus.PENDING,
                ShipmentStatus.CONFIRMED,
                ShipmentStatus.IN_TRANSIT
        );
        List<WaybillStatus> waybillStatuses = List.of(
                WaybillStatus.SHIPMENT_BOOKING,
                WaybillStatus.READY_FOR_DELIVERY,
                WaybillStatus.SHIPPING_FAILED,
                WaybillStatus.CANCELLATION_PENDING
        );
        long highWater = chowdeckTrackingHighWater.get();
        if (highWater == 0) {
            Long currentHighWater = shipmentRepository.findProviderTrackingHighWaterWaybillId(
                    ShippingProviderCode.CHOWDECK,
                    shipmentStatuses,
                    waybillStatuses
            );
            if (currentHighWater == null) {
                return;
            }
            highWater = currentHighWater;
            chowdeckTrackingHighWater.set(highWater);
            chowdeckTrackingCursor.set(0);
        }
        List<Long> trackingWaybillIds = shipmentRepository.findWaybillIdsForProviderTracking(
                ShippingProviderCode.CHOWDECK,
                shipmentStatuses,
                waybillStatuses,
                chowdeckTrackingCursor.get(),
                highWater,
                PageRequest.of(0, batchSize)
        );
        if (trackingWaybillIds.isEmpty()) {
            resetChowdeckTrackingCycle();
            return;
        }
        trackingWaybillIds.forEach(waybillId -> {
            try {
                shippingService.reconcileChowdeckTracking(waybillId);
            } catch (RuntimeException exception) {
                log.error("Unable to refresh Chowdeck waybill shipment: waybillId={}", waybillId);
            } finally {
                chowdeckTrackingCursor.set(waybillId);
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
