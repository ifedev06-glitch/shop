package com.example.shopsecure.service;

import com.example.shopsecure.dto.request.WithdrawalRequest;
import com.example.shopsecure.dto.response.WithdrawalResponse;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawalService {

    private final WithdrawalTransactionService withdrawalTransactionService;
    private final WithdrawalRepository withdrawalRepository;
    private final WalletRepository walletRepository;
    private final BankAccountRepository bankAccountRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final PaystackService paystackService;
    private final PinService pinService;

    public WithdrawalResponse withdraw(WithdrawalRequest request) {

        pinService.verifyPin(request.getPin());

        // Pre-Paystack DB work in transaction
        WithdrawalTransactionService.WithdrawalInitData data = withdrawalTransactionService.initializeWithdrawal(request);

        // Paystack call outside transaction
        String transferCode = null;
        try {
            Map<String, Object> response = paystackService.initiateTransfer(
                    data.recipientCode(),
                    request.getAmount(),
                    data.reference(),
                    "Vendor Withdrawal"
            );
            transferCode = response != null && response.get("transfer_code") != null
                    ? response.get("transfer_code").toString()
                    : null;
        } catch (Exception e) {
            log.error("Network issue initiating Paystack transfer for reference: {}", data.reference(), e);
        }

        // Post-Paystack DB work in transaction
        Withdrawal withdrawal = withdrawalTransactionService.completeWithdrawal(data.reference(), transferCode);

        return map(withdrawal);
    }

    // =========================
    // SUCCESS WEBHOOK
    // =========================
    @Transactional
    public void handleTransferSuccess(String transferCode) {

        Withdrawal withdrawal = withdrawalRepository.findByTransferCodeForUpdate(transferCode)
                .orElseThrow(() -> new ResourceNotFoundException("Withdrawal not found"));

        if (withdrawal.getStatus() == WithdrawalStatus.SUCCESS) return;

        withdrawal.setStatus(WithdrawalStatus.SUCCESS);
        withdrawalRepository.save(withdrawal);
        log.info("Transfer success: transferCode={}", transferCode);
    }

    // =========================
    // FAILED WEBHOOK (REFUND)
    // =========================
    @Transactional
    public void handleTransferFailed(String transferCode, String reason) {

        Withdrawal withdrawal = withdrawalRepository.findByTransferCodeForUpdate(transferCode)
                .orElseThrow(() -> new ResourceNotFoundException("Withdrawal not found"));

        if (withdrawal.getStatus() == WithdrawalStatus.FAILED) return;

        Wallet wallet = walletRepository.findByBusiness_IdForUpdate(
                withdrawal.getBusiness().getId()
        ).orElseThrow(() -> new ResourceNotFoundException("Wallet not found"));

        wallet.setBalance(
                wallet.getBalance().add(withdrawal.getAmount())
        );
        walletRepository.save(wallet);

        withdrawal.setStatus(WithdrawalStatus.FAILED);
        withdrawal.setFailureReason(reason);
        withdrawalRepository.save(withdrawal);
        log.warn("Transfer failed: transferCode={}, reason={}", transferCode, reason);
    }

    private Business getCurrentBusiness() {

        String email = SecurityContextHolder.getContext()
                .getAuthentication()
                .getName();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        return businessRepository.findByOwner_Id(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));
    }

    private WithdrawalResponse map(Withdrawal w) {
        return WithdrawalResponse.builder()
                .id(w.getId())
                .amount(w.getAmount())
                .reference(w.getReference())
                .transferCode(w.getTransferCode())
                .status(w.getStatus())
                .createdAt(w.getCreatedAt())
                .build();
    }

    public List<WithdrawalResponse> getMyWithdrawals() {

        Business business = getCurrentBusiness();

        return withdrawalRepository
                .findByBusiness_IdOrderByCreatedAtDesc(business.getId())
                .stream()
                .map(this::map)
                .toList();
    }
}