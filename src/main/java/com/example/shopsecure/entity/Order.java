package com.example.shopsecure.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(name = "orders", indexes = {
        @Index(name = "idx_orders_share_token", columnList = "share_token", unique = true),
        @Index(name = "idx_orders_creator_id", columnList = "creator_id"),
        @Index(name = "idx_orders_creator_created_id", columnList = "creator_id, created_at, id"),
        @Index(name = "idx_orders_status", columnList = "status")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "item_amount", nullable = false)
    private BigDecimal itemAmount;

    @Builder.Default
    @Column(nullable = false, length = 3)
    private String currency = "NGN";

    @Column(nullable = false, columnDefinition = "TEXT")
    private String itemDescription;

    @Column(nullable = false, length = 100)
    private String creatorFirstName;

    @Column(nullable = false, length = 100)
    private String creatorLastName;

    @Column(nullable = false, length = 20)
    private String creatorPhoneNumber;

    @Column(nullable = false)
    private String creatorEmail;

    @Column(nullable = false)
    private String creatorAddressLine1;

    @Column(nullable = false)
    private String creatorAddressLine2;

    @Column(nullable = false)
    private String creatorCity;

    @Column(nullable = false)
    private String creatorState;

    @Column(nullable = false)
    private String creatorCountry;

    @Column(nullable = false, length = 32)
    private String creatorPostalCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CreatorRole creatorRole;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrderFundingMode fundingMode = OrderFundingMode.DIRECT_DVA;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private OrderStatus status;

    @Column(nullable = false, unique = true, length = 64)
    private String shareToken;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creator_id", nullable = false)
    private User creator;

    private BigDecimal providerDeliveryFee;

    private BigDecimal deliveryMarkup;

    private BigDecimal deliveryFee;

    private BigDecimal serviceFee;

    private BigDecimal totalAmount;

    @Column(unique = true, length = 80)
    private String paymentReference;

    @Column(length = 4)
    private String vendorBankAccountLast4;

    @Column(length = 20)
    private String vendorBankCode;

    private String vendorBankName;

    private String vendorBankAccountName;

    @Column(length = 100)
    private String vendorPayoutRecipientCode;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private PaymentStatus paymentStatus = PaymentStatus.NOT_STARTED;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private SettlementStatus settlementStatus = SettlementStatus.NOT_STARTED;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "escrow_release_policy", nullable = false, length = 40)
    private EscrowReleasePolicy escrowReleasePolicy = EscrowReleasePolicy.BUYER_DELIVERY_CONFIRMATION;

    @Column(name = "paid_at")
    private Instant fundedAt;

    private Instant deliveryConfirmedAt;

    private Instant carrierPickupConfirmedAt;

    private Instant completedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;
}
