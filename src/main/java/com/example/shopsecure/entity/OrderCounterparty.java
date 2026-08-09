package com.example.shopsecure.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "order_counterparties", indexes = {
        @Index(name = "idx_order_counterparties_order_id", columnList = "order_id", unique = true),
        @Index(name = "idx_order_counterparties_user_id", columnList = "user_id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCounterparty {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false, length = 100)
    private String firstName;

    @Column(nullable = false, length = 100)
    private String lastName;

    @Column(nullable = false, length = 20)
    private String phoneNumber;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String addressLine1;

    @Column(nullable = false)
    private String addressLine2;

    @Column(nullable = false)
    private String city;

    @Column(nullable = false)
    private String state;

    @Column(nullable = false)
    private String country;

    @Column(nullable = false, length = 32)
    private String postalCode;

    @Column(columnDefinition = "TEXT")
    private String bankAccountEncrypted;

    @Column(length = 4)
    private String bankAccountLast4;

    @Column(length = 20)
    private String bankCode;

    private String bankName;

    private String bankAccountName;

    @Column(length = 100)
    private String payoutRecipientCode;

    @Builder.Default
    @Column(nullable = false)
    private boolean phoneVerified = false;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private java.time.Instant updatedAt;
}
