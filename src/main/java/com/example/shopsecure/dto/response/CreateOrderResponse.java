package com.example.shopsecure.dto.response;

import com.example.shopsecure.entity.CreatorRole;
import com.example.shopsecure.entity.OrderStatus;
import com.example.shopsecure.entity.OrderFundingMode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CreateOrderResponse(
        Long id,
        BigDecimal itemAmount,
        String currency,
        String itemDescription,
        CreatorRole creatorRole,
        CreatorRole counterpartyRole,
        OrderFundingMode fundingMode,
        OrderStatus status,
        String shareToken,
        String shareableLink,
        LocalDateTime createdAt
) {
}
