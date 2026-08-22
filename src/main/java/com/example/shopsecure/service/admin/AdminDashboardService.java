package com.example.shopsecure.service.admin;

import com.example.shopsecure.dto.response.admin.AdminDashboardResponse;
import com.example.shopsecure.entity.OrderStatus;
import com.example.shopsecure.entity.PaymentReceiptClassification;
import com.example.shopsecure.entity.SettlementStatus;
import com.example.shopsecure.entity.WalletWithdrawalStatus;
import com.example.shopsecure.provider.shipping.ShipmentStatus;
import com.example.shopsecure.repository.admin.AdminMetricsRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Service
public class AdminDashboardService {

    private final AdminMetricsRepository metricsRepository;
    private final TransactionTemplate readTransaction;
    private final Cache<String, AdminDashboardResponse> dashboardCache = Caffeine.newBuilder()
            .maximumSize(1)
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();

    public AdminDashboardService(
            AdminMetricsRepository metricsRepository,
            PlatformTransactionManager transactionManager
    ) {
        this.metricsRepository = metricsRepository;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.readTransaction.setTimeout(10);
    }

    public AdminDashboardResponse dashboard() {
        return dashboardCache.get("dashboard", ignored -> readTransaction.execute(status -> loadDashboard()));
    }

    private AdminDashboardResponse loadDashboard() {
        long totalUsers = metricsRepository.totalUsers();
        long verifiedUsers = metricsRepository.verifiedUsers();
        Map<String, Long> orderStatuses = metricsRepository.orderStatusCounts();
        Map<String, Long> withdrawalStatuses = metricsRepository.withdrawalStatusCounts();
        Map<String, Long> settlementStatuses = metricsRepository.settlementStatusCounts();
        Map<String, Long> shipmentStatuses = metricsRepository.shipmentStatusCounts();
        Map<String, Long> receiptClassifications = metricsRepository.paymentReceiptClassificationCounts();
        Map<String, BigDecimal> walletBalances = metricsRepository.availableWalletBalances();
        Map<String, BigDecimal> outstandingWithdrawals = metricsRepository.outstandingWithdrawalAmounts();

        return new AdminDashboardResponse(
                Instant.now(),
                new AdminDashboardResponse.UserMetrics(
                        totalUsers,
                        verifiedUsers,
                        totalUsers - verifiedUsers,
                        metricsRepository.adminUsers()
                ),
                new AdminDashboardResponse.OrderMetrics(
                        metricsRepository.totalOrders(),
                        orderStatuses,
                        metricsRepository.paymentStatusCounts(),
                        metricsRepository.confirmedOrderValues()
                ),
                new AdminDashboardResponse.FinanceMetrics(
                        walletBalances,
                        outstandingWithdrawals,
                        addMoney(walletBalances, outstandingWithdrawals),
                        metricsRepository.unsettledOrderPrincipal(),
                        metricsRepository.paymentReceiptAmountsRequiringReview()
                ),
                new AdminDashboardResponse.OperationsMetrics(
                        withdrawalStatuses,
                        settlementStatuses,
                        shipmentStatuses,
                        receiptClassifications,
                        value(orderStatuses, OrderStatus.MANUAL_REVIEW),
                        sum(withdrawalStatuses,
                                WalletWithdrawalStatus.DISPATCH_UNKNOWN,
                                WalletWithdrawalStatus.REQUIRES_ACTION,
                                WalletWithdrawalStatus.FAILED,
                                WalletWithdrawalStatus.REVERSED),
                        sum(settlementStatuses,
                                SettlementStatus.DISPATCH_UNKNOWN,
                                SettlementStatus.REQUIRES_ACTION,
                                SettlementStatus.FAILED,
                                SettlementStatus.REVERSED),
                        sum(shipmentStatuses,
                                ShipmentStatus.FAILED,
                                ShipmentStatus.UNKNOWN,
                                ShipmentStatus.CANCELLED),
                        sum(receiptClassifications,
                                PaymentReceiptClassification.UNDERPAID,
                                PaymentReceiptClassification.OVERPAID,
                                PaymentReceiptClassification.LATE,
                                PaymentReceiptClassification.DUPLICATE,
                                PaymentReceiptClassification.UNMATCHED,
                                PaymentReceiptClassification.FAILED)
                )
        );
    }

    private Map<String, BigDecimal> addMoney(
            Map<String, BigDecimal> first,
            Map<String, BigDecimal> second
    ) {
        Map<String, BigDecimal> result = new HashMap<>(first);
        second.forEach((currency, amount) -> result.merge(currency, amount, BigDecimal::add));
        return Map.copyOf(result);
    }

    private long value(Map<String, Long> values, Enum<?> key) {
        return values.getOrDefault(key.name(), 0L);
    }

    private long sum(Map<String, Long> values, Enum<?>... keys) {
        long total = 0;
        for (Enum<?> key : keys) {
            total += value(values, key);
        }
        return total;
    }
}
