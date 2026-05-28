package com.example.shopsecure.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class InitializePaymentResponse {
    private String authorizationUrl;
    private String reference;
    private String orderNumber;
}
