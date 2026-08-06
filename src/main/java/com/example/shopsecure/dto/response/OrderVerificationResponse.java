package com.example.shopsecure.dto.response;

import com.example.shopsecure.entity.OrderStatus;

public record OrderVerificationResponse(
        OrderStatus status,
        boolean phoneVerified
) {
}
