package com.example.shopsecure.provider.shipping;

import java.time.Instant;

public record ShipmentTrackingCheckpoint(
        ShipmentStatus status,
        Instant timestamp,
        String description,
        String location
) {
}
