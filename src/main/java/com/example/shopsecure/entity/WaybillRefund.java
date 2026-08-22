package com.example.shopsecure.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "waybill_refunds", indexes = {
        @Index(
                name = "idx_waybill_refunds_waybill_created",
                columnList = "waybill_id, created_at, id"
        ),
        @Index(name = "idx_waybill_refunds_status_updated", columnList = "status, updated_at")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WaybillRefund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "waybill_id", nullable = false)
    private Waybill waybill;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_attempt_id", nullable = false, unique = true)
    private WaybillPaymentAttempt paymentAttempt;

    @Column(unique = true, length = 100)
    private String providerRefundId;

    @Column(unique = true, length = 160)
    private String providerRefundReference;

    @Column(nullable = false, precision = 38, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private WaybillRefundStatus status = WaybillRefundStatus.REQUESTED;

    @Column(length = 500)
    private String reason;

    @Column(length = 500)
    private String failureReason;

    private LocalDateTime providerCreatedAt;

    private LocalDateTime providerUpdatedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime completedAt;

    @Version
    private long version;
}
