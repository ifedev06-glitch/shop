package com.example.shopsecure.dto.response;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
@Builder
public class CartResponse {

    private Long cartId;

    private String sessionId;

    private String businessName;

    private BigDecimal totalAmount;

    private List<CartItemResponse> items;

    private BigDecimal subtotal;

    private BigDecimal deliveryFee;


}