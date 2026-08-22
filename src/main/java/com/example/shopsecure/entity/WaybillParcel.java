package com.example.shopsecure.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "waybill_parcels")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WaybillParcel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "waybill_id", nullable = false, unique = true)
    private Waybill waybill;

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
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
