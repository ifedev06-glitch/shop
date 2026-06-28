package com.example.shopsecure.dto.request;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class CreateShippingRateRequest {

    private String area;
    private BigDecimal fee;

}
