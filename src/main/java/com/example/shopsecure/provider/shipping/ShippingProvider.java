package com.example.shopsecure.provider.shipping;

import com.example.shopsecure.provider.common.WebhookPayload;

import java.util.List;

public interface ShippingProvider {
    List<ShippingRateOption> getRates(ShipmentRequest request);

    ShipmentResponse createShipment(ShipmentRequest request, String selectedRateId);

    ShipmentTrackingStatus trackShipment(String shipmentId);

    ShipmentStatusUpdatedEvent handleWebhookEvent(WebhookPayload payload);
}
