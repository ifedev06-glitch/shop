package com.example.shopsecure.dto.response;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Builder
@Getter
@Setter
public class CreateCheckoutLinkResponse {

    private String slug;
    private String checkoutUrl;
    private BigDecimal totalAmount;
    private BigDecimal deliveryFee;
}