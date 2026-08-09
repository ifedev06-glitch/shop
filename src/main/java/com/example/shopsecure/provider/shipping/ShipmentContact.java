package com.example.shopsecure.provider.shipping;

public record ShipmentContact(
        String firstName,
        String lastName,
        String email,
        String phone
) {
}
