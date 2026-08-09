package com.example.shopsecure.controller;

import com.example.shopsecure.dto.request.ResolveBankAccountRequest;
import com.example.shopsecure.dto.response.BankResponse;
import com.example.shopsecure.dto.response.ResolvedBankAccountResponse;
import com.example.shopsecure.service.BankAccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class BankController {

    private final BankAccountService bankAccountService;

    @GetMapping("/banks")
    public List<BankResponse> listBanks() {
        return bankAccountService.listBanks();
    }

    @PostMapping("/bank-accounts/resolve")
    public ResolvedBankAccountResponse resolve(@Valid @RequestBody ResolveBankAccountRequest request) {
        return bankAccountService.resolve(request);
    }
}
