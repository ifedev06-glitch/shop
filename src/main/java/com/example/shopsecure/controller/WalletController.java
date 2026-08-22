package com.example.shopsecure.controller;

import com.example.shopsecure.dto.request.CreateWalletWithdrawalRequest;
import com.example.shopsecure.dto.request.ResolveBankAccountRequest;
import com.example.shopsecure.dto.request.WalletWithdrawalChallengeRequest;
import com.example.shopsecure.dto.response.WalletResponse;
import com.example.shopsecure.dto.response.WalletTransactionResponse;
import com.example.shopsecure.dto.response.PageResponse;
import com.example.shopsecure.dto.response.WalletWithdrawalResponse;
import com.example.shopsecure.dto.response.WalletWithdrawalChallengeResponse;
import com.example.shopsecure.dto.response.WithdrawalBankAccountResponse;
import com.example.shopsecure.service.WalletService;
import com.example.shopsecure.service.WalletWithdrawalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;
    private final WalletWithdrawalService withdrawalService;

    @GetMapping
    public WalletResponse getWallet() {
        return walletService.getCurrentWallet();
    }

    @GetMapping("/transactions")
    public PageResponse<WalletTransactionResponse> getTransactions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return walletService.getCurrentTransactions(page, size);
    }

    @PutMapping("/bank-account")
    public WithdrawalBankAccountResponse saveBankAccount(
            @Valid @RequestBody ResolveBankAccountRequest request
    ) {
        return withdrawalService.saveBankAccount(request);
    }

    @GetMapping("/bank-account")
    public WithdrawalBankAccountResponse getBankAccount() {
        return withdrawalService.getBankAccount();
    }

    @PostMapping("/withdrawals")
    public WalletWithdrawalResponse withdraw(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateWalletWithdrawalRequest request
    ) {
        return withdrawalService.createWithdrawal(request, idempotencyKey);
    }

    @PostMapping("/withdrawals/challenge")
    public WalletWithdrawalChallengeResponse requestWithdrawalChallenge(
            @Valid @RequestBody WalletWithdrawalChallengeRequest request
    ) {
        return withdrawalService.requestWithdrawalChallenge(request);
    }

    @GetMapping("/withdrawals")
    public PageResponse<WalletWithdrawalResponse> getWithdrawals(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return withdrawalService.getWithdrawals(page, size);
    }
}
