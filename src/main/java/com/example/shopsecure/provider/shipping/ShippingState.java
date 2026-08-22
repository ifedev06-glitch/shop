package com.example.shopsecure.provider.shipping;

public record ShippingState(
        String name,
        String code,
        String countryCode
) {
}
