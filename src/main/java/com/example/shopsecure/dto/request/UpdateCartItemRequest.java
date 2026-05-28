package com.example.shopsecure.dto.request;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateCartItemRequest {

    private String sessionId;

    private Long businessId;

    private Long productId;

    private Integer quantity;
}
