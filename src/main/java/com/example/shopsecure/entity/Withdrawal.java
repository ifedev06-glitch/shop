package com.example.shopsecure.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "withdrawals")
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