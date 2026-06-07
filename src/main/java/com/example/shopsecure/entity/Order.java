package com.example.shopsecure.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String orderNumber;

    @ManyToOne
    @JoinColumn(name = "business_id")
    private Business business;

    private String customerName;

    private String email;

    private String phoneNumber;

    @Column(columnDefinition = "TEXT")
    private String deliveryAddress;

    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    private String paymentStatus;

    private Boolean settled = false;

    private LocalDateTime createdAt;

    @Column(unique = true)
    private String paymentReference;

    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;


}