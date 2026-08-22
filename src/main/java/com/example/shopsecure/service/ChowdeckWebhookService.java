package com.example.shopsecure.service;

import com.example.shopsecure.exception.ServiceUnavailableException;
import com.example.shopsecure.provider.shipping.ShippingProviderCode;
import com.example.shopsecure.repository.OrderShipmentRepository;
import com.example.shopsecure.repository.WaybillShipmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChowdeckWebhookService {

    private final OrderShipmentRepository orderShipmentRepository;
    private final WaybillShipmentRepository waybillShipmentRepository;
    private final OrderShippingService orderShippingService;
    private final WaybillShippingService waybillShippingService;

    public void handle(String reference) {
        Optional<Long> orderId = orderShipmentRepository.findOrderIdByProviderAndProviderShipmentId(
                ShippingProviderCode.CHOWDECK,
                reference
        );
        Optional<Long> waybillId = waybillShipmentRepository.findWaybillIdByProviderAndProviderShipmentId(
                ShippingProviderCode.CHOWDECK,
                reference
        );

        if (orderId.isPresent() && waybillId.isPresent()) {
            log.error("Chowdeck webhook reference is owned by multiple shipment domains");
            return;
        }
        if (orderId.isEmpty() && waybillId.isEmpty()) {
            return;
        }

        try {
            if (orderId.isPresent()) {
                orderShippingService.reconcileChowdeckTracking(orderId.get());
            } else {
                waybillShippingService.reconcileChowdeckTracking(waybillId.orElseThrow());
            }
        } catch (RuntimeException exception) {
            log.warn("Unable to reconcile Chowdeck webhook callback: failureType={}",
                    exception.getClass().getSimpleName());
            throw new ServiceUnavailableException("Chowdeck webhook reconciliation is temporarily unavailable");
        }
    }
}
