package com.example.shopsecure.dto.request;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CheckoutLinkProductRequest {

    private Long productId;

    private Integer quantity;
}
