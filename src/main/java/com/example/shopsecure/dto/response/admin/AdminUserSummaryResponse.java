package com.example.shopsecure.dto.response.admin;

import com.example.shopsecure.entity.Role;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AdminUserSummaryResponse(
        Long id,
        String firstName,
        String lastName,
        String maskedEmail,
        String maskedPhoneNumber,
        Role role,
        boolean verified,
        LocalDateTime emailVerifiedAt,
        LocalDateTime createdAt,
        BigDecimal walletBalance,
        String walletCurrency
) {
}
