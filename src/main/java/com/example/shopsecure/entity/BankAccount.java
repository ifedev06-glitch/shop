package com.example.shopsecure.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "bank_accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "business_id")
    private Business business;

    private String accountName;

    private String accountNumber;

    private String bankCode;

    private String bankName;

    private String recipientCode;

    private LocalDateTime createdAt;
}