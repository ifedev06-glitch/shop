package com.example.shopsecure.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "checkout_links")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CheckoutLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String slug;

    @ManyToOne
    @JoinColumn(name = "business_id")
    private Business business;

    private BigDecimal totalAmount;

    private Boolean active;

    private LocalDateTime createdAt;

    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    private Boolean customLink;

}
