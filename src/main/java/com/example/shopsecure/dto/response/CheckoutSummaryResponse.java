package com.example.shopsecure.dto.response;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Builder
public class CheckoutSummaryResponse {

    private BigDecimal subtotal;

    private BigDecimal deliveryFee;

    private BigDecimal totalAmount;

    private Integer totalItems;
}