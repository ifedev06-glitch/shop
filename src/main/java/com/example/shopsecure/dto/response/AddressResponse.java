package com.example.shopsecure.dto.response;

public record AddressResponse(
        String line1,
        String line2,
        String city,
        String state,
        String country,
        String postalCode
) {
}
