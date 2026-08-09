package com.example.shopsecure.provider.shipping;

public record AddressValidationResult(
        boolean valid,
        ShipmentAddress suggestedAddress
) {
}
