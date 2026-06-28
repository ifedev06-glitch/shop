package com.example.shopsecure.controller;


import com.example.shopsecure.dto.response.WalletResponse;
import com.example.shopsecure.service.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/wallet")
@RequiredArgsConstructor
@Slf4j
public class WalletController {

    private final WalletService walletService;

    @GetMapping
    public WalletResponse getWallet() {
        log.info("Fetching wallet balance");
        return walletService.getWallet();
    }
}
