package com.example.shopsecure.dto.response.admin;

import com.example.shopsecure.dto.response.WaybillStatusHistoryResponse;
import com.example.shopsecure.entity.ShippingQuoteStatus;
import com.example.shopsecure.entity.WaybillInvitationKind;
import com.example.shopsecure.entity.WaybillPartyRole;
import com.example.shopsecure.entity.WaybillPartySource;
import com.example.shopsecure.entity.WaybillPayerType;
import com.example.shopsecure.entity.WaybillPaymentAttemptStatus;
import com.example.shopsecure.entity.WaybillPaymentClassification;
import com.example.shopsecure.entity.WaybillRefundStatus;
import com.example.shopsecure.provider.payment.PaymentTransactionStatus;
import com.example.shopsecure.provider.shipping.ShipmentStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record AdminWaybillDetailResponse(
        AdminWaybillSummaryResponse waybill,
        CreatorSnapshot creator,
        List<PartySnapshot> parties,
        ParcelSnapshot parcel,
        ShipmentSnapshot shipment,
        List<ShippingQuoteSnapshot> quotes,
        List<PaymentAttemptSnapshot> paymentAttempts,
        List<PaymentReceiptSnapshot> paymentReceipts,
        List<RefundSnapshot> refunds,
        List<InvitationSnapshot> invitations,
        List<WaybillStatusHistoryResponse> history
) {
    public record CreatorSnapshot(
            Long id,
            String firstName,
            String lastName,
            String maskedEmail,
            String maskedPhoneNumber,
            boolean enabled,
            LocalDateTime emailVerifiedAt,
            LocalDateTime createdAt
    ) {
    }

    public record PartySnapshot(
            Long id,
            Long userId,
            WaybillPartyRole role,
            WaybillPartySource source,
            String firstName,
            String lastName,
            String maskedEmail,
            String maskedPhoneNumber,
            String city,
            String state,
            String country,
            LocalDateTime detailsCompletedAt,
            LocalDateTime contactVerifiedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    public record ParcelSnapshot(
            Long id,
            BigDecimal weightKg,
            BigDecimal lengthCm,
            BigDecimal widthCm,
            BigDecimal heightCm,
            int quantity,
            String parcelType,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    public record ShipmentSnapshot(
            Long id,
            String provider,
            String providerShipmentId,
            ShipmentStatus status,
            String trackingReference,
            int bookingAttempt,
            LocalDateTime nextBookingAttemptAt,
            String failureReason,
            int cancellationAttempt,
            LocalDateTime nextCancellationAttemptAt,
            String cancellationFailureReason,
            LocalDateTime providerUpdatedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    public record ShippingQuoteSnapshot(
            Long id,
            String carrierName,
            String serviceDescription,
            BigDecimal providerAmount,
            BigDecimal deliveryMarkup,
            BigDecimal deliveryFee,
            BigDecimal serviceFee,
            BigDecimal totalAmount,
            String currency,
            String pickupEstimate,
            String deliveryEstimate,
            ShippingQuoteStatus status,
            LocalDateTime selectedAt,
            LocalDateTime expiresAt,
            LocalDateTime createdAt
    ) {
    }

    public record PaymentAttemptSnapshot(
            Long id,
            Long invitationId,
            WaybillPayerType payerType,
            String provider,
            String reference,
            String maskedCustomerEmail,
            BigDecimal amount,
            String currency,
            WaybillPaymentAttemptStatus status,
            String providerTransactionId,
            LocalDateTime nextRecheckAt,
            String failureReason,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime completedAt
    ) {
    }

    public record PaymentReceiptSnapshot(
            Long id,
            Long paymentAttemptId,
            String providerEventKey,
            String reference,
            BigDecimal amount,
            String currency,
            String channel,
            PaymentTransactionStatus providerStatus,
            WaybillPaymentClassification classification,
            LocalDateTime occurredAt,
            LocalDateTime createdAt
    ) {
    }

    public record RefundSnapshot(
            Long id,
            Long paymentAttemptId,
            String providerRefundId,
            String providerRefundReference,
            BigDecimal amount,
            String currency,
            WaybillRefundStatus status,
            String reason,
            String failureReason,
            LocalDateTime providerCreatedAt,
            LocalDateTime providerUpdatedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime completedAt
    ) {
    }

    public record InvitationSnapshot(
            Long id,
            WaybillInvitationKind kind,
            WaybillPartyRole targetPartyRole,
            boolean canPay,
            String stagedFirstName,
            String stagedLastName,
            String maskedStagedEmail,
            String maskedStagedPhoneNumber,
            int otpSendCount,
            int otpFailedAttemptCount,
            LocalDateTime otpLockedAt,
            LocalDateTime expiresAt,
            LocalDateTime verifiedAt,
            LocalDateTime detailsCompletedAt,
            LocalDateTime paymentCompletedAt,
            LocalDateTime revokedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }
}
