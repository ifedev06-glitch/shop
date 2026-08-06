package com.example.shopsecure.provider.payment;

public record VirtualAccountRequest(
        String customerName,
        String email,
        String phone,
        String customerReference
) {
}
