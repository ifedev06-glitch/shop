package com.example.shopsecure.dto.response;

public record VendorPayoutAccountResponse(
        String bankAccountLast4,
        String bankAccountName,
        String bankName
) {
}
