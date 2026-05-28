package com.example.shopsecure.controller;


import com.example.shopsecure.dto.response.WalletResponse;
import com.example.shopsecure.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;

    @GetMapping
    public WalletResponse getWallet() {
        return walletService.getWallet();
    }
}
