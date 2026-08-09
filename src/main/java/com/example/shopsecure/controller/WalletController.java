package com.example.shopsecure.controller;

import com.example.shopsecure.dto.response.WalletResponse;
import com.example.shopsecure.dto.response.WalletTransactionResponse;
import com.example.shopsecure.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;

    @GetMapping
    public WalletResponse getWallet() {
        return walletService.getCurrentWallet();
    }

    @GetMapping("/transactions")
    public List<WalletTransactionResponse> getTransactions() {
        return walletService.getCurrentTransactions();
    }
}
