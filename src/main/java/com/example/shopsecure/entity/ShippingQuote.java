package com.example.shopsecure.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "shipping_quotes", indexes = {
        @Index(name = "idx_shipping_quotes_provider_rate", columnList = "shipment_id, provider_rate_id", unique = true),
        @Index(name = "idx_shipping_quotes_shipment_status", columnList = "shipment_id, status")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShippingQuote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipment_id", nullable = false)
    private OrderShipment shipment;

    @Column(nullable = false, length = 100)
    private String providerRateId;

    @Column(length = 100)
    private String providerInternalId;

    @Column(nullable = false)
    private String carrierName;

    @Column(columnDefinition = "TEXT")
    private String carrierLogo;

    @Column(columnDefinition = "TEXT")
    private String serviceDescription;

    @Column(nullable = false)
    private BigDecimal providerAmount;

    @Column(nullable = false)
    private BigDecimal deliveryMarkup;

    @Column(nullable = false)
    private BigDecimal deliveryFee;

    @Column(nullable = false)
    private BigDecimal serviceFee;

    @Column(nullable = false)
    private BigDecimal buyerTotal;

    @Column(nullable = false, length = 3)
    private String currency;

    private String pickupEstimate;

    private String deliveryEstimate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ShippingQuoteStatus status;

    private Instant selectedAt;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private Instant createdAt;
}
