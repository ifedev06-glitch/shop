package com.example.shopsecure.dto.request;

import lombok.Data;

@Data
public class InitializePaymentRequest {
    private String orderNumber;
}