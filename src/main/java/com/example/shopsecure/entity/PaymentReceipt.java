package com.example.shopsecure.entity;

import com.example.shopsecure.provider.payment.PaymentTransactionStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payment_receipts", indexes = {
        @Index(name = "idx_payment_receipts_event", columnList = "provider_event_key", unique = true),
        @Index(name = "idx_payment_receipts_order_id", columnList = "order_id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;

    @Column(nullable = false, unique = true, length = 160)
    private String providerEventKey;

    @Column(nullable = false, length = 160)
    private String providerReference;

    @Column(length = 32)
    private String receiverAccountNumber;

    private BigDecimal amount;

    @Column(length = 3)
    private String currency;

    @Column(length = 50)
    private String channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_status", nullable = false, length = 40)
    private PaymentTransactionStatus providerStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private PaymentReceiptClassification classification;

    private Instant occurredAt;

    @Column(nullable = false)
    private Instant createdAt;
}
