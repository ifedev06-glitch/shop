package com.example.shopsecure.controller;

import com.example.shopsecure.dto.request.WithdrawalRequest;
import com.example.shopsecure.dto.response.WithdrawalResponse;
import com.example.shopsecure.service.WithdrawalService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/vendor/withdrawals")
@RequiredArgsConstructor
public class WithdrawalController {

    private final WithdrawalService withdrawalService;

    @PostMapping
    public WithdrawalResponse withdraw(
            @RequestBody WithdrawalRequest request
    ) {
        return withdrawalService.withdraw(request);
    }

    @GetMapping
    public List<WithdrawalResponse> getMyWithdrawals() {
        return withdrawalService.getMyWithdrawals();
    }
}