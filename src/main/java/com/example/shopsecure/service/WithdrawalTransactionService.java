package com.example.shopsecure.service;

import com.example.shopsecure.dto.request.WithdrawalRequest;
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
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawalTransactionService {

    private final WithdrawalRepository withdrawalRepository;
    private final WalletRepository walletRepository;
    private final BankAccountRepository bankAccountRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;

    @Transactional
    public WithdrawalInitData initializeWithdrawal(WithdrawalRequest request) {
        Business business = getCurrentBusiness();

        Wallet wallet = walletRepository.findByBusiness_IdForUpdate(business.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found"));

        BankAccount bankAccount = bankAccountRepository.findById(request.getBankAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Bank account not found"));

        if (!bankAccount.getBusiness().getId().equals(business.getId())) {
            throw new BusinessException("Unauthorized bank account");
        }

        BigDecimal amount = request.getAmount();

        if (wallet.getBalance().compareTo(amount) < 0) {
            throw new BusinessException("Insufficient balance");
        }

        wallet.setBalance(wallet.getBalance().subtract(amount));
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
    public Withdrawal completeWithdrawal(String reference, String transferCode) {
        Withdrawal withdrawal = withdrawalRepository.findByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Withdrawal not found"));

        if (transferCode != null) {
            withdrawal.setTransferCode(transferCode);
        } else {
            withdrawal.setFailureReason("Initial network timeout: awaiting reconciliation.");
        }
        return withdrawalRepository.save(withdrawal);
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

    public record WithdrawalInitData(String reference, String recipientCode) {}
}
