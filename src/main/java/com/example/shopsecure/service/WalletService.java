package com.example.shopsecure.service;

import com.example.shopsecure.entity.Order;
import com.example.shopsecure.entity.Wallet;
import com.example.shopsecure.entity.WalletTransaction;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.repository.WalletRepository;
import com.example.shopsecure.repository.WalletTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository transactionRepository;

    @Transactional
    public WalletMovement moveToProcessing(
            Long userId,
            Order order,
            BigDecimal requestedAmount,
            String idempotencyKey
    ) {
        WalletTransaction existing = transactionRepository.findByOrder_Id(order.getId()).orElse(null);
        if (existing != null) {
            return movement(existing);
        }

        BigDecimal amount = money(requestedAmount);
        Wallet wallet = walletRepository.findByUserIdForUpdate(userId)
                .orElseThrow(this::insufficientBalance);
        if (!wallet.getCurrency().equals(order.getCurrency())) {
            throw new BusinessException("Wallet currency does not match the order currency");
        }
        if (wallet.getAvailableBalance().compareTo(amount) < 0) {
            throw insufficientBalance();
        }

        BigDecimal availableBefore = money(wallet.getAvailableBalance());
        BigDecimal processingBefore = money(wallet.getProcessingBalance());
        BigDecimal availableAfter = money(availableBefore.subtract(amount));
        BigDecimal processingAfter = money(processingBefore.add(amount));

        wallet.setAvailableBalance(availableAfter);
        wallet.setProcessingBalance(processingAfter);
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);

        WalletTransaction transaction = transactionRepository.save(WalletTransaction.builder()
                .wallet(wallet)
                .order(order)
                .reference("escrow-order-" + order.getId())
                .idempotencyKey(idempotencyKey)
                .amount(amount)
                .availableBalanceBefore(availableBefore)
                .availableBalanceAfter(availableAfter)
                .processingBalanceBefore(processingBefore)
                .processingBalanceAfter(processingAfter)
                .createdAt(Instant.now())
                .build());
        return movement(transaction);
    }

    @Transactional(readOnly = true)
    public WalletMovement getOrderMovement(Long orderId) {
        return transactionRepository.findByOrder_Id(orderId)
                .map(this::movement)
                .orElseThrow(() -> new BusinessException("Wallet payment record was not found"));
    }

    private WalletMovement movement(WalletTransaction transaction) {
        return new WalletMovement(
                transaction.getAmount(),
                transaction.getAvailableBalanceAfter(),
                transaction.getProcessingBalanceAfter(),
                transaction.getWallet().getCurrency()
        );
    }

    private BusinessException insufficientBalance() {
        return new BusinessException(
                "Insufficient wallet balance. Top up your wallet before paying for this order."
        );
    }

    private BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    public record WalletMovement(
            BigDecimal amount,
            BigDecimal availableBalance,
            BigDecimal processingBalance,
            String currency
    ) {
    }
}
