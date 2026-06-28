package com.example.shopsecure.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "businesses", indexes = {
        @Index(name = "idx_businesses_owner_id", columnList = "owner_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Business {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String businessName;

    @Column(unique = true)
    private String storefrontSlug;

    private String description;

    private String phoneNumber;

    private String address;

    @OneToOne
    @JoinColumn(name = "owner_id")
    private User owner;

    private LocalDateTime createdAt;
}