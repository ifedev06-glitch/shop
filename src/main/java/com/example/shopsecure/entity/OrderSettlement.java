package com.example.shopsecure.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "order_settlements", indexes = {
        @Index(name = "idx_order_settlements_order_id", columnList = "order_id", unique = true),
        @Index(name = "idx_order_settlements_payout_reference", columnList = "payout_reference", unique = true),
        @Index(name = "idx_order_settlements_transfer_id", columnList = "transfer_id", unique = true),
        @Index(
                name = "idx_order_settlements_reconciliation_due",
                columnList = "settlement_type, status, next_reconciliation_at, id"
        )
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderSettlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private SettlementType settlementType;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private SettlementStatus status;

    @Column(nullable = false)
    private String idempotencyKey;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 40)
    private SettlementTrigger trigger = SettlementTrigger.BUYER_DELIVERY_CONFIRMATION;

    @Builder.Default
    @Column(nullable = false)
    private Instant triggeredAt = Instant.now();

    @Column(nullable = false, unique = true, length = 80)
    private String payoutReference;

    @Column(length = 100)
    private String transferId;

    @Builder.Default
    @Column(nullable = false)
    private int payoutAttempt = 0;

    private String failureReason;

    private Instant providerUpdatedAt;

    private Instant nextReconciliationAt;

    @Column(length = 64)
    private String reconciliationToken;

    @Column(length = 255)
    private String unexpectedPayoutReference;

    @Column(length = 100)
    private String unexpectedTransferId;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant completedAt;

    @Version
    private long version;
}
