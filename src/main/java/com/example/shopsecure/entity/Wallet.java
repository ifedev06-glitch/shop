package com.example.shopsecure.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "wallets")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "business_id")
    private Business business;

    @Column(nullable = false)
    private BigDecimal availableBalance = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal processingBalance = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal totalWithdrawn = BigDecimal.ZERO;

    @Column(nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    private WalletStatus status = WalletStatus.ACTIVE;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}