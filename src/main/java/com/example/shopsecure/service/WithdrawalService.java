package com.example.shopsecure.service;

import com.example.shopsecure.dto.request.WithdrawalRequest;
import com.example.shopsecure.dto.response.WithdrawalResponse;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
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

        // Pre-Paystack DB work in transaction
        WithdrawalInitData data = initializeWithdrawal(request);

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
        Withdrawal withdrawal = completeWithdrawal(data.reference(), transferCode);

        return map(withdrawal);
    }

    @Transactional
    protected WithdrawalInitData initializeWithdrawal(WithdrawalRequest request) {
        Business business = getCurrentBusiness();

        Wallet wallet = walletRepository.findByBusiness_IdForUpdate(business.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found"));

        BankAccount bankAccount = bankAccountRepository.findById(request.getBankAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Bank account not found"));

        if (!bankAccount.getBusiness().getId().equals(business.getId())) {
            throw new BusinessException("Unauthorized bank account");
        }

        BigDecimal amount = request.getAmount();

        if (wallet.getAvailableBalance().compareTo(amount) < 0) {
            throw new BusinessException("Insufficient available balance");
        }

        wallet.setAvailableBalance(wallet.getAvailableBalance().subtract(amount));
        walletRepository.save(wallet);

        String reference = "WD-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();

        Withdrawal withdrawal = Withdrawal.builder()
                .business(business)
                .amount(amount)
                .reference(reference)
                .status(WithdrawalStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();

        withdrawalRepository.save(withdrawal);
        log.info("Withdrawal initiated: amount={}, reference={}", amount, reference);

        return new WithdrawalInitData(reference, bankAccount.getRecipientCode());
    }

    @Transactional
    protected Withdrawal completeWithdrawal(String reference, String transferCode) {
        Withdrawal withdrawal = withdrawalRepository.findByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Withdrawal not found"));

        if (transferCode != null) {
            withdrawal.setTransferCode(transferCode);
        } else {
            withdrawal.setFailureReason("Initial network timeout: awaiting reconciliation.");
        }
        return withdrawalRepository.save(withdrawal);
    }

    private record WithdrawalInitData(String reference, String recipientCode) {}

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

        wallet.setAvailableBalance(
                wallet.getAvailableBalance().add(withdrawal.getAmount())
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