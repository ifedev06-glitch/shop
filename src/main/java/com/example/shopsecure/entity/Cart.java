package com.example.shopsecure.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "carts", indexes = {
        @Index(name = "idx_carts_session_business", columnList = "session_id, business_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Cart {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String sessionId;

    @ManyToOne
    @JoinColumn(name = "business_id")
    private Business business;

    private BigDecimal totalAmount = BigDecimal.ZERO;

    private LocalDateTime createdAt;
}
