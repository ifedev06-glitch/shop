package com.example.shopsecure.service;

import com.example.shopsecure.dto.request.WithdrawalRequest;
import com.example.shopsecure.dto.response.WithdrawalResponse;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class WithdrawalService {

    private final WithdrawalRepository withdrawalRepository;
    private final WalletRepository walletRepository;
    private final BankAccountRepository bankAccountRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final PaystackService paystackService;
    private final PinService pinService;


    public WithdrawalResponse withdraw(WithdrawalRequest request) {

        pinService.verifyPin(request.getPin());
        Business business = getCurrentBusiness();

        Wallet wallet = walletRepository.findByBusiness_IdForUpdate(business.getId())
                .orElseThrow(() -> new RuntimeException("Wallet not found"));

        BankAccount bankAccount = bankAccountRepository.findById(request.getBankAccountId())
                .orElseThrow(() -> new RuntimeException("Bank account not found"));

        if (!bankAccount.getBusiness().getId().equals(business.getId())) {
            throw new RuntimeException("Unauthorized bank account");
        }

        BigDecimal amount = request.getAmount();

        if (wallet.getAvailableBalance().compareTo(amount) < 0) {
            throw new RuntimeException("Insufficient available balance");
        }

        wallet.setAvailableBalance(wallet.getAvailableBalance().subtract(amount));
        walletRepository.save(wallet);

        String reference = "WD-" + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 12)
                .toUpperCase();

        Withdrawal withdrawal = Withdrawal.builder()
                .business(business)
                .amount(amount)
                .reference(reference)
                .status(WithdrawalStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();

        withdrawalRepository.save(withdrawal);

        String transferCode = null;
        try {
            Map<String, Object> response = paystackService.initiateTransfer(
                    bankAccount.getRecipientCode(),
                    amount,
                    reference,
                    "Vendor Withdrawal"
            );

            transferCode = response != null && response.get("transfer_code") != null
                    ? response.get("transfer_code").toString()
                    : null;

            withdrawal.setTransferCode(transferCode);
            withdrawalRepository.save(withdrawal);

        } catch (Exception e) {
            log.error("Network issue initiating Paystack transfer for reference: {}", reference, e);

            withdrawal.setFailureReason("Initial network timeout: awaiting reconciliation.");
            withdrawalRepository.save(withdrawal);
        }

        return map(withdrawal);

    }

    // =========================
    // SUCCESS WEBHOOK
    // =========================
    public void handleTransferSuccess(String transferCode) {

        Withdrawal withdrawal = withdrawalRepository.findByTransferCodeForUpdate(transferCode)
                .orElseThrow(() -> new RuntimeException("Withdrawal not found"));

        if (withdrawal.getStatus() == WithdrawalStatus.SUCCESS) return;

        withdrawal.setStatus(WithdrawalStatus.SUCCESS);
        withdrawalRepository.save(withdrawal);
    }

    // =========================
    // FAILED WEBHOOK (REFUND)
    // =========================
    public void handleTransferFailed(String transferCode, String reason) {

        Withdrawal withdrawal = withdrawalRepository.findByTransferCodeForUpdate(transferCode)
                .orElseThrow(() -> new RuntimeException("Withdrawal not found"));

        if (withdrawal.getStatus() == WithdrawalStatus.FAILED) return;

        Wallet wallet = walletRepository.findByBusiness_IdForUpdate(
                withdrawal.getBusiness().getId()
        ).orElseThrow(() -> new RuntimeException("Wallet not found"));

        wallet.setAvailableBalance(
                wallet.getAvailableBalance().add(withdrawal.getAmount())
        );
        walletRepository.save(wallet);

        withdrawal.setStatus(WithdrawalStatus.FAILED);
        withdrawal.setFailureReason(reason);
        withdrawalRepository.save(withdrawal);
    }

    private Business getCurrentBusiness() {

        String email = SecurityContextHolder.getContext()
                .getAuthentication()
                .getName();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        return businessRepository.findByOwner_Id(user.getId())
                .orElseThrow(() -> new RuntimeException("Business not found"));
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

    public java.util.List<WithdrawalResponse> getMyWithdrawals() {

        Business business = getCurrentBusiness();

        return withdrawalRepository
                .findByBusiness_IdOrderByCreatedAtDesc(business.getId())
                .stream()
                .map(this::map)
                .toList();
    }

}