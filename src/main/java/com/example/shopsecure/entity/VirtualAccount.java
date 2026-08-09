package com.example.shopsecure.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "virtual_accounts", indexes = {
        @Index(name = "idx_virtual_accounts_user_id", columnList = "user_id", unique = true),
        @Index(name = "idx_virtual_accounts_number", columnList = "account_number", unique = true)
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VirtualAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(nullable = false, unique = true, length = 32)
    private String accountNumber;

    @Column(nullable = false)
    private String bankName;

    @Column(nullable = false)
    private String accountName;

    private Instant expiresAt;

    @Column(nullable = false)
    private Instant createdAt;
}
