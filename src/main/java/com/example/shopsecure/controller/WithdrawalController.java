package com.example.shopsecure.controller;

import com.example.shopsecure.dto.request.WithdrawalRequest;
import com.example.shopsecure.dto.response.WithdrawalResponse;
import com.example.shopsecure.service.WithdrawalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/vendor/withdrawals")
@RequiredArgsConstructor
@Slf4j
public class WithdrawalController {

    private final WithdrawalService withdrawalService;

    @PostMapping
    public WithdrawalResponse withdraw(
            @RequestBody WithdrawalRequest request
    ) {
        log.info("Withdrawal request: amount={}", request.getAmount());
        return withdrawalService.withdraw(request);
    }

    @GetMapping
    public List<WithdrawalResponse> getMyWithdrawals() {
        log.info("Fetching vendor withdrawal history");
        return withdrawalService.getMyWithdrawals();
    }
}