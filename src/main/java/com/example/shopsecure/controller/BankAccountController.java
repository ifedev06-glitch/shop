package com.example.shopsecure.controller;

import com.example.shopsecure.dto.request.CreateBankAccountRequest;
import com.example.shopsecure.dto.response.BankAccountResponse;
import com.example.shopsecure.service.BankAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/vendor/bank-account")
@Slf4j
public class BankAccountController {

    private final BankAccountService bankAccountService;

    @PostMapping
    public BankAccountResponse saveBankAccount(
            @RequestBody CreateBankAccountRequest request
    ) {
        log.info("Saving bank account for vendor");
        return bankAccountService.saveBankAccount(request);
    }

    @GetMapping
    public BankAccountResponse getBankAccount() {
        log.info("Fetching vendor bank account");
        return bankAccountService.getBankAccount();
    }
}