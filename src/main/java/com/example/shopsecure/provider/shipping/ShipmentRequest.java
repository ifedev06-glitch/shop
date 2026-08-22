package com.example.shopsecure.provider.shipping;

public record ShipmentRequest(
        ShipmentAddress pickupAddress,
        ShipmentAddress deliveryAddress,
        ParcelDetails parcelDetails,
        ShipmentContact senderContact,
        ShipmentContact recipientContact,
        String orderReference,
        ShipmentUserAction userAction
) {
    public ShipmentRequest(
            ShipmentAddress pickupAddress,
            ShipmentAddress deliveryAddress,
            ParcelDetails parcelDetails,
            ShipmentContact senderContact,
            ShipmentContact recipientContact,
            String orderReference
    ) {
        this(
                pickupAddress,
                deliveryAddress,
                parcelDetails,
                senderContact,
                recipientContact,
                orderReference,
                ShipmentUserAction.SENDING
        );
    }
}
