package com.example.shopsecure.provider.shipping;

public record ShipmentAddress(
        String line1,
        String line2,
        String city,
        String state,
        String country,
        String postalCode
) {
}
