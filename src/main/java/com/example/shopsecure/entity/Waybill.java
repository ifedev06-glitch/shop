package com.example.shopsecure.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "waybills", uniqueConstraints = {
        @UniqueConstraint(
                name = "uq_waybill_creator_creation_key",
                columnNames = {"creator_id", "creation_idempotency_key"}
        )
}, indexes = {
        @Index(name = "idx_waybills_creator_created", columnList = "creator_id, created_at, id"),
        @Index(name = "idx_waybills_status_updated", columnList = "status, updated_at"),
        @Index(name = "idx_waybills_payment_due", columnList = "payment_status, payment_due_at")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Waybill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 36)
    private String publicId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creator_id", nullable = false)
    private User creator;

    @Column(nullable = false, length = 255)
    private String creationIdempotencyKey;

    @Column(nullable = false, length = 64)
    private String creationRequestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WaybillPartyRole creatorRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private WaybillPayerType payerType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, precision = 38, scale = 2)
    private BigDecimal declaredValue;

    @Builder.Default
    @Column(nullable = false, length = 3)
    private String currency = "NGN";

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private WaybillStatus status = WaybillStatus.AWAITING_PARTY_DETAILS;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private WaybillPaymentStatus paymentStatus = WaybillPaymentStatus.NOT_STARTED;

    @Column(precision = 38, scale = 2)
    private BigDecimal providerDeliveryFee;

    @Column(precision = 38, scale = 2)
    private BigDecimal deliveryMarkup;

    @Column(precision = 38, scale = 2)
    private BigDecimal deliveryFee;

    @Column(precision = 38, scale = 2)
    private BigDecimal serviceFee;

    @Column(precision = 38, scale = 2)
    private BigDecimal totalAmount;

    private LocalDateTime paymentDueAt;

    @Column(unique = true, length = 160)
    private String fundingPaymentReference;

    private LocalDateTime fundedAt;

    private LocalDateTime completedAt;

    @Column(length = 255)
    private String cancellationIdempotencyKey;

    @Column(length = 500)
    private String cancellationReason;

    private LocalDateTime cancellationRequestedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Version
    private long version;
}
