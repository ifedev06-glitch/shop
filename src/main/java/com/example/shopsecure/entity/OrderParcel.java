package com.example.shopsecure.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "order_parcels", indexes = {
        @Index(name = "idx_order_parcels_order_id", columnList = "order_id", unique = true)
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderParcel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal weightKg;

    @Column(precision = 12, scale = 2)
    private BigDecimal lengthCm;

    @Column(precision = 12, scale = 2)
    private BigDecimal widthCm;

    @Column(precision = 12, scale = 2)
    private BigDecimal heightCm;

    @Builder.Default
    @Column(nullable = false)
    private int quantity = 1;

    @Builder.Default
    @Column(nullable = false, length = 20)
    private String parcelType = "parcel";

    @Column(nullable = false)
    private Instant createdAt;
}
