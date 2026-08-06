package com.example.shopsecure.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "wallet_transactions", indexes = {
        @Index(name = "idx_wallet_transactions_order_id", columnList = "order_id", unique = true),
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

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    @Column(nullable = false, unique = true)
    private String reference;

    @Column(nullable = false)
    private String idempotencyKey;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private BigDecimal availableBalanceBefore;

    @Column(nullable = false)
    private BigDecimal availableBalanceAfter;

    @Column(nullable = false)
    private BigDecimal processingBalanceBefore;

    @Column(nullable = false)
    private BigDecimal processingBalanceAfter;

    @Column(nullable = false)
    private Instant createdAt;
}
