package com.example.shopsecure.event;

import com.example.shopsecure.entity.SettlementType;
import com.example.shopsecure.entity.SettlementTrigger;

public record VendorSettlementCompletedEvent(
        Long orderId,
        SettlementType settlementType,
        SettlementTrigger trigger
) {
}
