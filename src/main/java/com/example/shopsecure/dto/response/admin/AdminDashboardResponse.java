package com.example.shopsecure.dto.response.admin;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record AdminDashboardResponse(
        Instant asOf,
        UserMetrics users,
        OrderMetrics orders,
        FinanceMetrics finance,
        OperationsMetrics operations
) {
    public record UserMetrics(
            long total,
            long verified,
            long pendingVerification,
            long admins
    ) {
    }

    public record OrderMetrics(
            long total,
            Map<String, Long> byStatus,
            Map<String, Long> byPaymentStatus,
            Map<String, OrderValue> confirmedValueByCurrency
    ) {
    }

    public record OrderValue(
            BigDecimal grossAmount,
            BigDecimal itemAmount,
            BigDecimal serviceFees,
            BigDecimal deliveryMarkup
    ) {
    }

    public record FinanceMetrics(
            Map<String, BigDecimal> availableWalletBalance,
            Map<String, BigDecimal> outstandingWithdrawalAmount,
            Map<String, BigDecimal> customerObligation,
            Map<String, BigDecimal> unsettledOrderPrincipal,
            Map<String, Map<String, BigDecimal>> paymentReceiptsRequiringReview
    ) {
    }

    public record OperationsMetrics(
            Map<String, Long> withdrawalsByStatus,
            Map<String, Long> settlementsByStatus,
            Map<String, Long> shipmentsByStatus,
            Map<String, Long> paymentReceiptsByClassification,
            long manualReviewOrders,
            long withdrawalAttentionRequired,
            long settlementAttentionRequired,
            long shippingAttentionRequired,
            long anomalousPaymentReceipts
    ) {
    }
}
