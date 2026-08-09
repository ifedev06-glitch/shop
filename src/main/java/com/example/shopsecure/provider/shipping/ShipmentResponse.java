package com.example.shopsecure.provider.shipping;

import java.time.Instant;

public record ShipmentResponse(
        String shipmentId,
        ShipmentStatus status,
        String trackingReference,
        String trackingUrl,
        Instant updatedAt
) {
}
