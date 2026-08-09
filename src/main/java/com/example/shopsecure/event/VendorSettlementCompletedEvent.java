package com.example.shopsecure.event;

import com.example.shopsecure.entity.SettlementType;

public record VendorSettlementCompletedEvent(
        Long orderId,
        SettlementType settlementType
) {
}
