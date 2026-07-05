package com.example.shopsecure.dto.response;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Builder
public class WalletResponse {

    private Long walletId;

    private String businessName;

    private BigDecimal balance;
}