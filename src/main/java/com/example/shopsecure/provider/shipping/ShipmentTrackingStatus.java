package com.example.shopsecure.provider.shipping;

import java.time.Instant;
import java.util.List;

public record ShipmentTrackingStatus(
        String shipmentId,
        ShipmentStatus currentStatus,
        Instant lastUpdated,
        List<ShipmentTrackingCheckpoint> history,
        String trackingReference,
        String trackingUrl
) {
    public ShipmentTrackingStatus(
            ShipmentStatus currentStatus,
            Instant lastUpdated,
            List<ShipmentTrackingCheckpoint> history
    ) {
        this(null, currentStatus, lastUpdated, history, null, null);
    }

    public ShipmentTrackingStatus {
        history = history == null ? List.of() : List.copyOf(history);
    }
}
