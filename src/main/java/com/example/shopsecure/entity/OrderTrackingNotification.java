package com.example.shopsecure.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "order_tracking_notifications", indexes = {
        @Index(name = "idx_order_tracking_notifications_due", columnList = "status, next_attempt_at")
}, uniqueConstraints = {
        @UniqueConstraint(
                name = "uq_order_tracking_notification_role_type",
                columnNames = {"order_id", "participant_role", "notification_type"}
        )
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderTrackingNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(name = "participant_role", nullable = false, length = 20)
    private CreatorRole participantRole;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 40)
    private OrderTrackingNotificationType notificationType = OrderTrackingNotificationType.TRACKING_READY;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderTrackingNotificationStatus status = OrderTrackingNotificationStatus.PENDING;

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
