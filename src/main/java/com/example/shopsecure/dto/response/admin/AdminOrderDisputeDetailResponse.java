package com.example.shopsecure.dto.response.admin;

import com.example.shopsecure.entity.OrderDisputeNotificationAudience;
import com.example.shopsecure.entity.OrderDisputeNotificationStatus;

import java.time.Instant;
import java.util.List;

public record AdminOrderDisputeDetailResponse(
        AdminOrderDisputeSummaryResponse summary,
        String itemDescription,
        String reason,
        PartySnapshot buyer,
        PartySnapshot vendor,
        List<NotificationSnapshot> notifications
) {
    public record PartySnapshot(
            String firstName,
            String lastName,
            String maskedEmail,
            String maskedPhoneNumber
    ) {
    }

    public record NotificationSnapshot(
            OrderDisputeNotificationAudience audience,
            OrderDisputeNotificationStatus status,
            int attemptCount,
            Instant nextAttemptAt,
            Instant sentAt,
            String failureReason
    ) {
    }
}
