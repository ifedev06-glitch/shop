package com.example.shopsecure.dto.response;

import com.example.shopsecure.entity.CreatorRole;
import com.example.shopsecure.entity.OrderStatus;
import com.example.shopsecure.entity.PaymentStatus;
import com.example.shopsecure.entity.SettlementStatus;

import java.math.BigDecimal;

public record PublicOrderResponse(
        BigDecimal itemAmount,
        String itemDescription,
        String currency,
        OrderStatus status,
        CreatorRole creatorRole,
        CreatorRole counterpartyRole,
        boolean counterpartyVerified,
        PaymentStatus paymentStatus,
        SettlementStatus settlementStatus
) {
}
