package com.example.shopsecure.dto.response;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class BankAccountResponse {

    private Long id;

    private String accountName;

    private String accountNumber;

    private String bankCode;

    private String bankName;
}