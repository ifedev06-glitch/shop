package com.example.shopsecure.provider.shipping;

import java.time.Instant;
import java.util.List;

public record ShipmentTrackingStatus(
        ShipmentStatus currentStatus,
        Instant lastUpdated,
        List<ShipmentTrackingCheckpoint> history
) {
    public ShipmentTrackingStatus {
        history = history == null ? List.of() : List.copyOf(history);
    }
}
