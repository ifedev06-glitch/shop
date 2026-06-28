package com.example.shopsecure.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CheckoutSummaryRequest {

    @NotBlank(message = "Session ID is required")
    private String sessionId;

    @NotNull(message = "Business ID is required")
    private Long businessId;

    @NotBlank(message = "Shipping area is required")
    private String shippingArea;

}