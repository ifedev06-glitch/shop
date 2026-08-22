package com.example.shopsecure.dto.response;

import com.example.shopsecure.provider.shipping.ShipmentStatus;

import java.time.Instant;

public record ShippingStatusResponse(
        ShipmentStatus status,
        String carrierName,
        String trackingReference,
        String trackingUrl,
        String shopSecureTrackingUrl,
        Instant trackingAvailableAt,
        String deliveryEstimate,
        Instant updatedAt
) {
}
