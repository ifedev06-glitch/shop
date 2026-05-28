package com.example.shopsecure.dto.request;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CheckoutRequest {

    private String sessionId;

    private Long businessId;

    private String customerName;

    private String email;

    private String phoneNumber;

    private String deliveryAddress;
}
