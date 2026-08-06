package com.example.shopsecure.provider.shipping;

import java.time.Instant;

public record ShipmentStatusUpdatedEvent(
        String shipmentId,
        ShipmentStatus status,
        Instant occurredAt,
        boolean relevant
) {
    public static ShipmentStatusUpdatedEvent ignored() {
        return new ShipmentStatusUpdatedEvent(null, ShipmentStatus.UNKNOWN, Instant.now(), false);
    }
}
