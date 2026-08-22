package com.example.shopsecure.entity;

import com.example.shopsecure.provider.shipping.ShipmentStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "waybill_shipments", indexes = {
        @Index(
                name = "idx_waybill_shipments_booking_due",
                columnList = "status, next_booking_attempt_at"
        ),
        @Index(
                name = "idx_waybill_shipments_cancellation_due",
                columnList = "status, next_cancellation_attempt_at"
        )
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WaybillShipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "waybill_id", nullable = false, unique = true)
    private Waybill waybill;

    @Column(nullable = false, length = 40)
    private String provider;

    @Column(unique = true, length = 100)
    private String providerShipmentId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ShipmentStatus status = ShipmentStatus.DRAFT;

    @Column(length = 255)
    private String trackingReference;

    @Column(columnDefinition = "TEXT")
    private String trackingUrl;

    private LocalDateTime providerUpdatedAt;

    @Builder.Default
    @Column(nullable = false)
    private int bookingAttempt = 0;

    private LocalDateTime nextBookingAttemptAt;

    @Column(length = 500)
    private String failureReason;

    @Builder.Default
    @PositiveOrZero
    @Column(nullable = false)
    private int cancellationAttempt = 0;

    private LocalDateTime nextCancellationAttemptAt;

    @Size(max = 500)
    @Column(length = 500)
    private String cancellationFailureReason;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Version
    private long version;
}
