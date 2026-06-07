package com.example.shopsecure.dto.response;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Builder
@Getter
@Setter
public class CheckoutPaymentResponse {

    private String orderNumber;

    private String authorizationUrl;

    private String reference;
}