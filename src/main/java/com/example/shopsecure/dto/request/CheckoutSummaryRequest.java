package com.example.shopsecure.dto.request;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CheckoutSummaryRequest {

    private String sessionId;

    private Long businessId;
}