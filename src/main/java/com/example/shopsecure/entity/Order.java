package com.example.shopsecure.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "orders", indexes = {
        @Index(name = "idx_orders_order_number", columnList = "order_number"),
        @Index(name = "idx_orders_business_id", columnList = "business_id"),
        @Index(name = "idx_orders_status_payment_status", columnList = "status, payment_status")
})
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

    @Column(nullable = false)
    private Boolean stockDeducted = false;

    private LocalDateTime createdAt;

    @Column(unique = true)
    private String paymentReference;

    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String shippingArea;

    private BigDecimal shippingFee;

}