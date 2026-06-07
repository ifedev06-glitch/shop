package com.example.shopsecure.controller;

import com.example.shopsecure.dto.request.CreateBankAccountRequest;
import com.example.shopsecure.dto.response.BankAccountResponse;
import com.example.shopsecure.service.BankAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/vendor/bank-account")
public class BankAccountController {

    private final BankAccountService bankAccountService;

    @PostMapping
    public BankAccountResponse saveBankAccount(
            @RequestBody CreateBankAccountRequest request
    ) {
        return bankAccountService.saveBankAccount(request);
    }

    @GetMapping
    public BankAccountResponse getBankAccount() {
        return bankAccountService.getBankAccount();
    }
}