package com.example.shopsecure.dto.response;

import com.example.shopsecure.entity.CreatorRole;
import com.example.shopsecure.entity.OrderStatus;

import java.time.Instant;

public record OrderVerificationResponse(
        OrderStatus status,
        CreatorRole counterpartyRole,
        boolean phoneVerified,
        String guestAccessToken,
        Instant guestAccessTokenExpiresAt
) {
}
