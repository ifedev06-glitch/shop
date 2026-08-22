package com.example.shopsecure.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "wallet_withdrawals", indexes = {
        @Index(name = "idx_wallet_withdrawals_wallet_created", columnList = "wallet_id, created_at, id"),
        @Index(name = "idx_wallet_withdrawals_reference", columnList = "reference", unique = true),
        @Index(name = "idx_wallet_withdrawals_transfer_id", columnList = "transfer_id", unique = true),
        @Index(name = "idx_wallet_withdrawals_wallet_key", columnList = "wallet_id, idempotency_key", unique = true)
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletWithdrawal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private WalletWithdrawalStatus status;

    @Column(nullable = false, length = 255)
    private String idempotencyKey;

    @Column(nullable = false, unique = true, length = 80)
    private String reference;

    @Column(length = 100)
    private String transferId;

    @Column(nullable = false, length = 100)
    private String payoutRecipientCode;

    @Column(nullable = false, length = 4)
    private String accountNumberLast4;

    @Column(nullable = false)
    private String bankName;

    @Column(nullable = false)
    private String accountName;

    @Builder.Default
    @Column(nullable = false)
    private int dispatchAttempt = 0;

    private String failureReason;

    private Instant providerUpdatedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant completedAt;

    @Version
    private long version;
}
