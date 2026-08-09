package com.example.shopsecure.provider.shipping;

import java.math.BigDecimal;

public record ShippingRateOption(
        String providerRateId,
        String providerInternalId,
        String carrierName,
        String carrierLogo,
        String serviceDescription,
        BigDecimal providerAmount,
        String currency,
        String pickupEstimate,
        String deliveryEstimate
) {
}
