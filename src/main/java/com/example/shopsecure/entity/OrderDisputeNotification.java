package com.example.shopsecure.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "order_dispute_notifications", indexes = {
        @Index(name = "idx_order_dispute_notifications_due", columnList = "status, next_attempt_at, id")
}, uniqueConstraints = {
        @UniqueConstraint(
                name = "uq_order_dispute_notification_audience",
                columnNames = {"dispute_id", "audience"}
        )
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderDisputeNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dispute_id", nullable = false)
    private OrderDispute dispute;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderDisputeNotificationAudience audience;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderDisputeNotificationStatus status = OrderDisputeNotificationStatus.PENDING;

    @Column(nullable = false, length = 100)
    private String recipientFirstName;

    @Column(nullable = false, length = 255)
    private String recipientEmail;

    @Builder.Default
    @Column(nullable = false)
    private int attemptCount = 0;

    private Instant nextAttemptAt;

    private UUID dispatchToken;

    private Instant firstAttemptAt;

    @Column(length = 500)
    private String failureReason;

    private Instant sentAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;
}
