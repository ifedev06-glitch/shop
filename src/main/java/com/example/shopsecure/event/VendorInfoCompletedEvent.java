package com.example.shopsecure.event;

public record VendorInfoCompletedEvent(
        String customerEmail,
        String customerFirstName,
        String itemDescription
) {
}
