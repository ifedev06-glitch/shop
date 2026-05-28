package com.example.shopsecure.dto.response;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Builder
public class CartItemResponse {

    private Long productId;

    private String productName;

    private String imageUrl;

    private BigDecimal price;

    private Integer quantity;

    private BigDecimal subtotal;
}