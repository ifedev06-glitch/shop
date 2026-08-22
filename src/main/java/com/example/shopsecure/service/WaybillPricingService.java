package com.example.shopsecure.service;

import com.example.shopsecure.entity.Waybill;
import com.example.shopsecure.entity.WaybillShippingQuote;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class WaybillPricingService {

    private final BigDecimal deliveryMarkup;
    private final BigDecimal serviceFee;
    private final String currency;

    public WaybillPricingService(
            @Value("${shipping.pricing.delivery-markup:1000.00}") BigDecimal deliveryMarkup,
            @Value("${shipping.pricing.service-fee:500.00}") BigDecimal serviceFee,
            @Value("${shipping.pricing.currency:NGN}") String currency
    ) {
        this.deliveryMarkup = money(deliveryMarkup);
        this.serviceFee = money(serviceFee);
        this.currency = currency;
    }

    public Pricing price(Waybill waybill, BigDecimal providerAmount) {
        if (waybill == null || waybill.getCurrency() == null
                || !currency.equalsIgnoreCase(waybill.getCurrency())) {
            throw new IllegalArgumentException("Waybill currency does not match shipping pricing currency");
        }
        BigDecimal providerFee = money(providerAmount);
        BigDecimal deliveryFee = money(providerFee.add(deliveryMarkup));
        BigDecimal total = money(deliveryFee.add(serviceFee));
        return new Pricing(providerFee, deliveryMarkup, deliveryFee, serviceFee, total, currency);
    }

    public void freeze(Waybill waybill, WaybillShippingQuote quote) {
        waybill.setProviderDeliveryFee(quote.getProviderAmount());
        waybill.setDeliveryMarkup(quote.getDeliveryMarkup());
        waybill.setDeliveryFee(quote.getDeliveryFee());
        waybill.setServiceFee(quote.getServiceFee());
        waybill.setTotalAmount(quote.getTotalAmount());
    }

    private static BigDecimal money(BigDecimal amount) {
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("Price must not be negative");
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    public record Pricing(
            BigDecimal providerFee,
            BigDecimal deliveryMarkup,
            BigDecimal deliveryFee,
            BigDecimal serviceFee,
            BigDecimal total,
            String currency
    ) {
    }
}
