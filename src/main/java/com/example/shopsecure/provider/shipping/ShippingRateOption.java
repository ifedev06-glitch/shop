package com.example.shopsecure.provider.shipping;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ShippingRateOption(
        String carrierName,
        BigDecimal cost,
        LocalDate estimatedDeliveryStart,
        LocalDate estimatedDeliveryEnd,
        String rateId
) {
}
