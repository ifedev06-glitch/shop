package com.example.shopsecure.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "waybill_shipping_quotes", uniqueConstraints = {
        @UniqueConstraint(
                name = "uq_waybill_quote_provider_rate",
                columnNames = {"shipment_id", "provider_rate_id"}
        )
}, indexes = {
        @Index(
                name = "idx_waybill_quotes_shipment_status",
                columnList = "shipment_id, status, expires_at"
        )
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WaybillShippingQuote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipment_id", nullable = false)
    private WaybillShipment shipment;

    @Column(nullable = false, length = 100)
    private String providerRateId;

    @Column(length = 100)
    private String providerInternalId;

    @Column(nullable = false, length = 255)
    private String carrierName;

    @Column(columnDefinition = "TEXT")
    private String carrierLogo;

    @Column(columnDefinition = "TEXT")
    private String serviceDescription;

    @Column(nullable = false, precision = 38, scale = 2)
    private BigDecimal providerAmount;

    @Column(nullable = false, precision = 38, scale = 2)
    private BigDecimal deliveryMarkup;

    @Column(nullable = false, precision = 38, scale = 2)
    private BigDecimal deliveryFee;

    @Column(nullable = false, precision = 38, scale = 2)
    private BigDecimal serviceFee;

    @Column(nullable = false, precision = 38, scale = 2)
    private BigDecimal totalAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(length = 255)
    private String pickupEstimate;

    @Column(length = 255)
    private String deliveryEstimate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ShippingQuoteStatus status;

    private LocalDateTime selectedAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
