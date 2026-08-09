package com.example.shopsecure.provider.shipping;

import com.example.shopsecure.provider.common.WebhookPayload;

import java.util.List;
import java.math.BigDecimal;

public interface ShippingProvider {
    AddressValidationResult validateAddress(ShipmentAddress address);

    ShipmentResponse createQuickShipment(ShipmentRequest request);

    List<ShippingRateOption> getRates(String shipmentId, String currency);

    BigDecimal getWalletBalance();

    ShipmentResponse arrangePickup(String shipmentId, String rateId);

    ShipmentTrackingStatus trackShipment(String shipmentId);

    ShipmentResponse cancelShipment(String shipmentId);

    ShipmentStatusUpdatedEvent handleWebhookEvent(WebhookPayload payload);
}
