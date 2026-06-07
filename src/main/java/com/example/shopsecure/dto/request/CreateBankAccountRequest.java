package com.example.shopsecure.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateBankAccountRequest {

    @NotBlank(message = "Account number is required")
    @Size(min = 10, max = 10, message = "Account number must be 10 digits")
    private String accountNumber;

    @NotBlank(message = "Bank code is required")
    private String bankCode;


    @NotBlank
    private String pin;

}