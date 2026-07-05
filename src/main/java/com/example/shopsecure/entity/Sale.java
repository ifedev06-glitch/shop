package com.example.shopsecure.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "sales")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Sale {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String saleNumber;

    @ManyToOne
    @JoinColumn(name = "business_id")
    private Business business;

    private BigDecimal totalAmount;

    private BigDecimal discount;

    private String paymentMethod;

    private String customerName;

    private String customerPhone;

    @Column(columnDefinition = "TEXT")
    private String notes;

    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "sale", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SaleItem> items;
}
