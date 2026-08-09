package com.example.shopsecure.dto.response;

public record CounterpartyResponse(
        String firstName,
        String lastName,
        String email,
        String phoneNumber,
        AddressResponse address,
        String bankAccountLast4,
        String bankAccountName,
        String bankName,
        boolean phoneVerified
) {
}
