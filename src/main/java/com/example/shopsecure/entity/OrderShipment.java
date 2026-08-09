package com.example.shopsecure.entity;

import com.example.shopsecure.provider.shipping.ShipmentStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "order_shipments", indexes = {
        @Index(name = "idx_order_shipments_order_id", columnList = "order_id", unique = true),
        @Index(name = "idx_order_shipments_provider_id", columnList = "provider_shipment_id", unique = true)
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderShipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    @Column(nullable = false, length = 40)
    private String provider;

    @Column(unique = true, length = 100)
    private String providerShipmentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ShipmentStatus status;

    private String trackingReference;

    @Column(columnDefinition = "TEXT")
    private String trackingUrl;

    private Instant providerUpdatedAt;

    @Builder.Default
    @Column(nullable = false)
    private int bookingAttempt = 0;

    private Instant nextBookingAttemptAt;

    private String failureReason;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;
}
