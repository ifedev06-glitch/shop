package com.example.shopsecure.dto.response.admin;

import com.example.shopsecure.provider.shipping.ShipmentStatus;

import java.time.Instant;

public record AdminShipmentResponse(
        Long id,
        Long orderId,
        String provider,
        String providerShipmentId,
        ShipmentStatus status,
        String trackingReference,
        int bookingAttempt,
        Instant nextBookingAttemptAt,
        String failureReason,
        Instant providerUpdatedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
