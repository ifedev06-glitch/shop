package com.example.shopsecure.controller;

import com.example.shopsecure.dto.response.PageResponse;
import com.example.shopsecure.dto.response.admin.*;
import com.example.shopsecure.dto.request.UpdateChowdeckControlRequest;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.provider.payment.PaymentTransactionStatus;
import com.example.shopsecure.provider.shipping.ShipmentStatus;
import com.example.shopsecure.service.admin.AdminDashboardService;
import com.example.shopsecure.service.admin.AdminReadService;
import com.example.shopsecure.service.ChowdeckControlService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminController {

    private final AdminDashboardService dashboardService;
    private final AdminReadService readService;
    private final ChowdeckControlService chowdeckControlService;

    @ModelAttribute
    void disableCaching(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
    }

    @GetMapping("/dashboard")
    public AdminDashboardResponse dashboard() {
        return dashboardService.dashboard();
    }

    @GetMapping("/users")
    public PageResponse<AdminUserSummaryResponse> users(
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) Boolean verified,
            @RequestParam(required = false) String email,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return readService.users(role, verified, email, page, size);
    }

    @GetMapping("/users/{userId}")
    public AdminUserDetailResponse user(@PathVariable Long userId) {
        return readService.user(userId);
    }

    @GetMapping("/orders")
    public PageResponse<AdminOrderSummaryResponse> orders(
            @RequestParam(required = false) Long creatorId,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) PaymentStatus paymentStatus,
            @RequestParam(required = false) SettlementStatus settlementStatus,
            @RequestParam(required = false) OrderFundingMode fundingMode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return readService.orders(
                creatorId,
                status,
                paymentStatus,
                settlementStatus,
                fundingMode,
                page,
                size
        );
    }

    @GetMapping("/orders/{orderId}")
    public AdminOrderDetailResponse order(@PathVariable Long orderId) {
        return readService.order(orderId);
    }

    @GetMapping("/waybills")
    public PageResponse<AdminWaybillSummaryResponse> waybills(
            @RequestParam(required = false) Long creatorId,
            @RequestParam(required = false) WaybillStatus status,
            @RequestParam(required = false) WaybillPaymentStatus paymentStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return readService.waybills(creatorId, status, paymentStatus, page, size);
    }

    @GetMapping("/waybills/{waybillId}")
    public AdminWaybillDetailResponse waybill(@PathVariable Long waybillId) {
        return readService.waybill(waybillId);
    }

    @GetMapping("/wallet-transactions")
    public PageResponse<AdminWalletTransactionResponse> walletTransactions(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long orderId,
            @RequestParam(required = false) WalletTransactionType transactionType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return readService.walletTransactions(userId, orderId, transactionType, page, size);
    }

    @GetMapping("/withdrawals")
    public PageResponse<AdminWithdrawalResponse> withdrawals(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) WalletWithdrawalStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return readService.withdrawals(userId, status, page, size);
    }

    @GetMapping("/settlements")
    public PageResponse<AdminSettlementResponse> settlements(
            @RequestParam(required = false) Long orderId,
            @RequestParam(required = false) SettlementStatus status,
            @RequestParam(required = false) SettlementType settlementType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return readService.settlements(orderId, status, settlementType, page, size);
    }

    @GetMapping("/shipments")
    public PageResponse<AdminShipmentResponse> shipments(
            @RequestParam(required = false) Long orderId,
            @RequestParam(required = false) ShipmentStatus status,
            @RequestParam(required = false) String provider,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return readService.shipments(orderId, status, provider, page, size);
    }

    @GetMapping("/payment-attempts")
    public PageResponse<AdminPaymentAttemptResponse> paymentAttempts(
            @RequestParam(required = false) Long orderId,
            @RequestParam(required = false) PaymentAttemptStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return readService.paymentAttempts(orderId, status, page, size);
    }

    @GetMapping("/payment-receipts")
    public PageResponse<AdminPaymentReceiptResponse> paymentReceipts(
            @RequestParam(required = false) Long orderId,
            @RequestParam(required = false) PaymentReceiptClassification classification,
            @RequestParam(required = false) PaymentTransactionStatus providerStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return readService.paymentReceipts(orderId, classification, providerStatus, page, size);
    }

    @GetMapping("/shipping/chowdeck-control")
    public AdminChowdeckControlResponse chowdeckControl() {
        return chowdeckControlService.get();
    }

    @PutMapping("/shipping/chowdeck-control")
    public AdminChowdeckControlResponse updateChowdeckControl(
            @Valid @RequestBody UpdateChowdeckControlRequest request
    ) {
        return chowdeckControlService.update(request);
    }

    @GetMapping("/shipping/chowdeck-control/history")
    public PageResponse<AdminChowdeckControlHistoryResponse> chowdeckControlHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return chowdeckControlService.history(page, size);
    }
}
