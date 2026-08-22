package com.example.shopsecure.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "wallet_transactions", indexes = {
        @Index(name = "idx_wallet_transactions_order_id", columnList = "order_id"),
        @Index(name = "idx_wallet_transactions_order_type", columnList = "order_id, transaction_type", unique = true),
        @Index(name = "idx_wallet_transactions_withdrawal_type", columnList = "withdrawal_id, transaction_type", unique = true),
        @Index(name = "idx_wallet_transactions_wallet_created_id", columnList = "wallet_id, created_at, id"),
        @Index(name = "idx_wallet_transactions_reference", columnList = "reference", unique = true)
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_receipt_id", unique = true)
    private PaymentReceipt paymentReceipt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "withdrawal_id")
    private WalletWithdrawal withdrawal;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 40)
    private WalletTransactionType transactionType = WalletTransactionType.ORDER_RELEASE;

    @Column(nullable = false, unique = true)
    private String reference;

    @Column(nullable = false)
    private String idempotencyKey;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private BigDecimal balanceBefore;

    @Column(nullable = false)
    private BigDecimal balanceAfter;

    @Column(nullable = false)
    private Instant createdAt;
}


// waybill option