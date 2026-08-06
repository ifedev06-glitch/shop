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

    @Column(nullable = false)
    private BigDecimal amount;

    @Builder.Default
    @Column(nullable = false, length = 3)
    private String currency = "NGN";

    @Column(nullable = false, columnDefinition = "TEXT")
    private String itemDescription;

    @Column(nullable = false, length = 20)
    private String buyerPhoneNumber;

    @Column(nullable = false)
    private String buyerEmail;

    @Column(nullable = false)
    private String deliveryAddressLine1;

    @Column(nullable = false)
    private String deliveryCity;

    @Column(nullable = false)
    private String deliveryState;

    @Column(nullable = false)
    private String deliveryCountry;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CreatorRole creatorRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private OrderStatus status;

    @Column(nullable = false, unique = true, length = 64)
    private String shareToken;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creator_id", nullable = false)
    private User creator;

    private String paymentIdempotencyKey;

    private Instant paidAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;
}
