package com.example.shopsecure.dto.response;


import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor


public class ShippingAreaResponse {


    private Long id;

    private String area;

    private BigDecimal fee;


}