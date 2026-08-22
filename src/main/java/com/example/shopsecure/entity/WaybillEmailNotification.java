package com.example.shopsecure.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "waybill_email_notifications", indexes = {
        @Index(
                name = "idx_waybill_email_notifications_due",
                columnList = "status, next_attempt_at"
        )
}, uniqueConstraints = {
        @UniqueConstraint(
                name = "uq_waybill_email_notification_party_type",
                columnNames = {"waybill_id", "party_id", "notification_type"}
        )
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WaybillEmailNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "waybill_id", nullable = false)
    private Waybill waybill;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "party_id", nullable = false)
    private WaybillParty party;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 40)
    private WaybillEmailNotificationType notificationType;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WaybillEmailNotificationStatus status = WaybillEmailNotificationStatus.PENDING;

    @Column(nullable = false, length = 255)
    private String recipientEmail;

    @Column(nullable = false, length = 255)
    private String subject;

    @Column(columnDefinition = "TEXT")
    private String htmlBody;

    @Builder.Default
    @Column(nullable = false)
    private int attemptCount = 0;

    private LocalDateTime nextAttemptAt;

    private UUID dispatchToken;

    private LocalDateTime firstAttemptAt;

    @Column(length = 500)
    private String failureReason;

    private LocalDateTime sentAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Version
    private long version;
}
