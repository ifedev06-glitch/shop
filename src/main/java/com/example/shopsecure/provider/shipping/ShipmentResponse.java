package com.example.shopsecure.provider.shipping;

public record ShipmentResponse(
        String shipmentId,
        ShipmentStatus status,
        String trackingReference
) {
}
