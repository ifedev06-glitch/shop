package com.example.shopsecure.dto.response;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Builder
@Getter
@Setter
public class CheckoutLinkDetailsResponse {

    private String businessName;

    private String slug;

    private String title;

    private String description;

    private Boolean customLink;

    private BigDecimal totalAmount;

    private BigDecimal deliveryFee;
    private BigDecimal subtotalAmount;

    private List<CheckoutLinkItemResponse> items;
}