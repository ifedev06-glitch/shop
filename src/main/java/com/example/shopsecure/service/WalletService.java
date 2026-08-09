package com.example.shopsecure.service;

import com.example.shopsecure.dto.response.WalletResponse;
import com.example.shopsecure.dto.response.WalletTransactionResponse;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.repository.WalletRepository;
import com.example.shopsecure.repository.WalletTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository transactionRepository;
    private final OrderService orderService;

    @Transactional
    public WalletTransaction creditOrderRelease(Order order, String idempotencyKey) {
        WalletTransaction existing = transactionRepository
                .findByOrder_IdAndTransactionType(order.getId(), WalletTransactionType.ORDER_RELEASE)
                .orElse(null);
        if (existing != null) {
            return existing;
        }
        Wallet wallet = walletRepository.findByUserIdForUpdate(order.getCreator().getId())
                .orElseThrow(() -> new BusinessException("Vendor wallet was not found"));
        if (!wallet.getCurrency().equalsIgnoreCase(order.getCurrency())) {
            throw new BusinessException("Wallet currency does not match the order currency");
        }
        BigDecimal amount = money(order.getItemAmount());
        BigDecimal before = money(wallet.getBalance());
        BigDecimal after = money(before.add(amount));
        wallet.setBalance(after);
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);
        return transactionRepository.save(WalletTransaction.builder()
                .wallet(wallet)
                .order(order)
                .transactionType(WalletTransactionType.ORDER_RELEASE)
                .reference("order-release-" + order.getId())
                .idempotencyKey(idempotencyKey)
                .amount(amount)
                .balanceBefore(before)
                .balanceAfter(after)
                .createdAt(Instant.now())
                .build());
    }

    @Transactional
    public WalletTransaction creditDvaTransfer(User owner, PaymentReceipt receipt) {
        WalletTransaction existing = transactionRepository.findByPaymentReceipt_Id(receipt.getId()).orElse(null);
        if (existing != null) {
            return existing;
        }
        Wallet wallet = walletRepository.findByUserIdForUpdate(owner.getId())
                .orElseThrow(() -> new BusinessException("Wallet was not found for virtual-account owner"));
        if (!wallet.getCurrency().equalsIgnoreCase(receipt.getCurrency())) {
            throw new BusinessException("Wallet currency does not match the incoming transfer currency");
        }
        BigDecimal amount = money(receipt.getAmount());
        if (amount.signum() <= 0) {
            throw new BusinessException("Wallet top-up amount must be greater than zero");
        }
        BigDecimal before = money(wallet.getBalance());
        BigDecimal after = money(before.add(amount));
        Instant now = Instant.now();
        wallet.setBalance(after);
        wallet.setUpdatedAt(now);
        walletRepository.save(wallet);
        return transactionRepository.save(WalletTransaction.builder()
                .wallet(wallet)
                .paymentReceipt(receipt)
                .transactionType(WalletTransactionType.DVA_CREDIT)
                .reference("dva-credit-" + receipt.getProviderEventKey())
                .idempotencyKey(receipt.getProviderEventKey())
                .amount(amount)
                .balanceBefore(before)
                .balanceAfter(after)
                .createdAt(now)
                .build());
    }

    @Transactional
    public WalletDebitResult debitOrderPayment(Order order, String idempotencyKey) {
        WalletTransaction existing = transactionRepository
                .findByOrder_IdAndTransactionType(order.getId(), WalletTransactionType.ORDER_PAYMENT_DEBIT)
                .orElse(null);
        if (existing != null) {
            if (!existing.getIdempotencyKey().equals(idempotencyKey)) {
                throw new BusinessException("A different idempotency key was already used for this wallet payment");
            }
            return new WalletDebitResult(
                    true,
                    existing,
                    existing.getBalanceAfter(),
                    BigDecimal.ZERO.setScale(2),
                    existing.getCreatedAt()
            );
        }

        Wallet wallet = walletRepository.findByUserIdForUpdate(order.getCreator().getId())
                .orElseThrow(() -> new BusinessException("Buyer wallet was not found"));
        if (!wallet.getCurrency().equalsIgnoreCase(order.getCurrency())) {
            throw new BusinessException("Wallet currency does not match the order currency");
        }
        BigDecimal amount = money(order.getTotalAmount());
        BigDecimal before = money(wallet.getBalance());
        if (before.compareTo(amount) < 0) {
            return new WalletDebitResult(
                    false,
                    null,
                    before,
                    money(amount.subtract(before)),
                    wallet.getUpdatedAt()
            );
        }

        BigDecimal after = money(before.subtract(amount));
        Instant now = Instant.now();
        wallet.setBalance(after);
        wallet.setUpdatedAt(now);
        walletRepository.save(wallet);
        WalletTransaction transaction = transactionRepository.save(WalletTransaction.builder()
                .wallet(wallet)
                .order(order)
                .transactionType(WalletTransactionType.ORDER_PAYMENT_DEBIT)
                .reference("order-payment-" + order.getId())
                .idempotencyKey(idempotencyKey)
                .amount(amount)
                .balanceBefore(before)
                .balanceAfter(after)
                .createdAt(now)
                .build());
        return new WalletDebitResult(true, transaction, after, BigDecimal.ZERO.setScale(2), now);
    }

    @Transactional(readOnly = true)
    public WalletResponse getCurrentWallet() {
        User user = orderService.currentUser();
        Wallet wallet = walletRepository.findByUser_Id(user.getId())
                .orElseThrow(() -> new BusinessException("Wallet was not found"));
        return new WalletResponse(wallet.getBalance(), wallet.getCurrency(), wallet.getUpdatedAt());
    }

    @Transactional(readOnly = true)
    public List<WalletTransactionResponse> getCurrentTransactions() {
        User user = orderService.currentUser();
        return transactionRepository.findByWallet_User_IdOrderByCreatedAtDesc(user.getId()).stream()
                .map(transaction -> new WalletTransactionResponse(
                        transaction.getReference(),
                        transaction.getTransactionType(),
                        transaction.getAmount(),
                        transaction.getBalanceBefore(),
                        transaction.getBalanceAfter(),
                        transaction.getOrder() == null ? null : transaction.getOrder().getId(),
                        transaction.getCreatedAt()
                ))
                .toList();
    }

    private BigDecimal money(BigDecimal amount) {
        if (amount == null) {
            throw new BusinessException("Wallet amount is required");
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    public record WalletDebitResult(
            boolean funded,
            WalletTransaction transaction,
            BigDecimal balance,
            BigDecimal shortfall,
            Instant occurredAt
    ) {
    }
}
