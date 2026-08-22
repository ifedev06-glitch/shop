package com.example.shopsecure.entity;

import com.example.shopsecure.provider.payment.PaymentTransactionStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "waybill_payment_receipts", indexes = {
        @Index(
                name = "idx_waybill_receipts_waybill_created",
                columnList = "waybill_id, created_at, id"
        ),
        @Index(name = "idx_waybill_receipts_attempt", columnList = "attempt_id"),
        @Index(name = "idx_waybill_receipts_reference", columnList = "reference")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WaybillPaymentReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "waybill_id", nullable = false)
    private Waybill waybill;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attempt_id", nullable = false)
    private WaybillPaymentAttempt attempt;

    @Column(nullable = false, unique = true, length = 160)
    private String providerEventKey;

    @Column(nullable = false, length = 160)
    private String reference;

    @Column(precision = 38, scale = 2)
    private BigDecimal amount;

    @Column(length = 3)
    private String currency;

    @Column(length = 50)
    private String channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private PaymentTransactionStatus providerStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private WaybillPaymentClassification classification;

    private LocalDateTime occurredAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
