package com.example.shopsecure.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "withdrawals", indexes = {
        @Index(name = "idx_withdrawals_business_created", columnList = "business_id, created_at"),
        @Index(name = "idx_withdrawals_transfer_code", columnList = "transfer_code")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Withdrawal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "business_id")
    private Business business;

    private BigDecimal amount;

    private String reference;

    private String transferCode;

    @Enumerated(EnumType.STRING)
    private WithdrawalStatus status;

    private LocalDateTime createdAt;

    private String failureReason;


}