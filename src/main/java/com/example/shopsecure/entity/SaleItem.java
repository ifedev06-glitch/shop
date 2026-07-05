package com.example.shopsecure.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "sale_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SaleItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "sale_id")
    private Sale sale;

    @ManyToOne
    @JoinColumn(name = "product_id")
    private Product product;

    private String productName;

    private Integer quantity;

    private BigDecimal sellingPrice;

    private BigDecimal costPrice;

    private BigDecimal subtotal;
}
