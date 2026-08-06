package com.example.shopsecure.provider.shipping;

public record ShipmentRequest(
        ShipmentAddress pickupAddress,
        ShipmentAddress deliveryAddress,
        ParcelDetails parcelDetails,
        ShipmentContact senderContact,
        ShipmentContact recipientContact
) {
}
