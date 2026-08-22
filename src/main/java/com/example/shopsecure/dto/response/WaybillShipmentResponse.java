package com.example.shopsecure.dto.response;

import com.example.shopsecure.provider.shipping.ShipmentStatus;

import java.time.LocalDateTime;

public record WaybillShipmentResponse(
        String provider,
        String providerShipmentId,
        ShipmentStatus status,
        String trackingReference,
        String trackingUrl,
        String failureReason,
        LocalDateTime providerUpdatedAt,
        LocalDateTime updatedAt
) {
}
