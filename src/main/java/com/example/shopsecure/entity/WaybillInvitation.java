package com.example.shopsecure.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "waybill_invitations", indexes = {
        @Index(
                name = "idx_waybill_invitations_active",
                columnList = "waybill_id, revoked_at, expires_at"
        ),
        @Index(name = "idx_waybill_invitations_otp", columnList = "otp_challenge_id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WaybillInvitation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "waybill_id", nullable = false)
    private Waybill waybill;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private WaybillInvitationKind kind;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private WaybillPartyRole targetPartyRole;

    @Column(nullable = false)
    private boolean canPay;

    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(unique = true, length = 64)
    private String accessTokenHash;

    @Column(length = 100)
    private String stagedFirstName;

    @Column(length = 100)
    private String stagedLastName;

    @Column(length = 255)
    private String stagedEmail;

    @Column(length = 20)
    private String stagedPhoneNumber;

    @Column(length = 64)
    private String otpChallengeId;

    @Builder.Default
    @Column(nullable = false)
    private int otpSendCount = 0;

    @Builder.Default
    @Column(nullable = false)
    private int otpFailedAttemptCount = 0;

    private LocalDateTime otpLockedAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    private LocalDateTime verifiedAt;

    private LocalDateTime detailsCompletedAt;

    private LocalDateTime paymentCompletedAt;

    private LocalDateTime revokedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Version
    private long version;
}
