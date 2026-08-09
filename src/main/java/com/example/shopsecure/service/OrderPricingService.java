package com.example.shopsecure.service;

import com.example.shopsecure.entity.Order;
import com.example.shopsecure.entity.ShippingQuote;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class OrderPricingService {

    private final BigDecimal deliveryMarkup;
    private final BigDecimal serviceFee;
    private final String currency;

    public OrderPricingService(
            @Value("${shipping.pricing.delivery-markup:1000.00}") BigDecimal deliveryMarkup,
            @Value("${shipping.pricing.service-fee:500.00}") BigDecimal serviceFee,
            @Value("${shipping.pricing.currency:NGN}") String currency
    ) {
        this.deliveryMarkup = money(deliveryMarkup);
        this.serviceFee = money(serviceFee);
        this.currency = currency;
    }

    public Pricing price(Order order, BigDecimal providerAmount) {
        BigDecimal providerFee = money(providerAmount);
        BigDecimal deliveryFee = money(providerFee.add(deliveryMarkup));
        BigDecimal total = money(order.getItemAmount().add(deliveryFee).add(serviceFee));
        return new Pricing(providerFee, deliveryMarkup, deliveryFee, serviceFee, total, currency);
    }

    public void freeze(Order order, ShippingQuote quote) {
        order.setProviderDeliveryFee(quote.getProviderAmount());
        order.setDeliveryMarkup(quote.getDeliveryMarkup());
        order.setDeliveryFee(quote.getDeliveryFee());
        order.setServiceFee(quote.getServiceFee());
        order.setTotalAmount(quote.getBuyerTotal());
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
