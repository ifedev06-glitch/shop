package com.example.shopsecure.dto.response;

import com.example.shopsecure.entity.WithdrawalStatus;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Builder
public class WithdrawalResponse {

    private Long id;

    private BigDecimal amount;

    private String reference;

    private String transferCode;

    private WithdrawalStatus status;

    private LocalDateTime createdAt;
}