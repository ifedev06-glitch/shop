package com.example.shopsecure.dto.response;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Builder
public class OrderItemResponse {

    private String productName;

    private Integer quantity;

    private BigDecimal price;

    private BigDecimal subtotal;
}
