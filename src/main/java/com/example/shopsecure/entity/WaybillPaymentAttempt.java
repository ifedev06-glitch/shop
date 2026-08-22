package com.example.shopsecure.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "waybill_payment_attempts", uniqueConstraints = {
        @UniqueConstraint(
                name = "uq_waybill_payment_attempt_key",
                columnNames = {"waybill_id", "idempotency_key"}
        )
}, indexes = {
        @Index(
                name = "idx_waybill_attempts_waybill_status",
                columnList = "waybill_id, status, created_at"
        ),
        @Index(name = "idx_waybill_attempts_invitation", columnList = "invitation_id"),
        @Index(name = "idx_waybill_attempts_recheck_due", columnList = "status, next_recheck_at")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WaybillPaymentAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "waybill_id", nullable = false)
    private Waybill waybill;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invitation_id")
    private WaybillInvitation invitation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private WaybillPayerType payerType;

    @Builder.Default
    @Column(nullable = false, length = 40)
    private String provider = "PAYSTACK";

    @Column(nullable = false, unique = true, length = 160)
    private String reference;

    @Column(nullable = false, length = 255)
    private String idempotencyKey;

    @Column(columnDefinition = "TEXT")
    private String authorizationUrl;

    @Column(length = 160)
    private String accessCode;

    @Column(nullable = false, length = 255)
    private String customerEmail;

    @Column(nullable = false, precision = 38, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private WaybillPaymentAttemptStatus status = WaybillPaymentAttemptStatus.INITIALIZING;

    @Column(length = 100)
    private String providerTransactionId;

    private LocalDateTime nextRecheckAt;

    @Column(length = 500)
    private String failureReason;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime completedAt;

    @Version
    private long version;
}
