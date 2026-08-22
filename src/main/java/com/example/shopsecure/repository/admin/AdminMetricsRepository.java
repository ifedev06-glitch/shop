package com.example.shopsecure.repository.admin;

import com.example.shopsecure.dto.response.admin.AdminDashboardResponse;
import com.example.shopsecure.entity.PaymentReceiptClassification;
import com.example.shopsecure.entity.PaymentStatus;
import com.example.shopsecure.entity.SettlementStatus;
import com.example.shopsecure.entity.WalletWithdrawalStatus;
import com.example.shopsecure.provider.payment.PaymentTransactionStatus;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class AdminMetricsRepository {

    private final EntityManager entityManager;

    public long totalUsers() {
        return count("select count(u) from User u");
    }

    public long verifiedUsers() {
        return count("select count(u) from User u where u.emailVerifiedAt is not null");
    }

    public long adminUsers() {
        return count("select count(u) from User u where u.role = com.example.shopsecure.entity.Role.ADMIN");
    }

    public long totalOrders() {
        return count("select count(o) from Order o");
    }

    public Map<String, Long> orderStatusCounts() {
        return groupedCounts("select o.status, count(o) from Order o group by o.status");
    }

    public Map<String, Long> paymentStatusCounts() {
        return groupedCounts("select o.paymentStatus, count(o) from Order o group by o.paymentStatus");
    }

    public Map<String, AdminDashboardResponse.OrderValue> confirmedOrderValues() {
        List<Object[]> rows = entityManager.createQuery("""
                        select o.currency,
                               coalesce(sum(o.totalAmount), 0),
                               coalesce(sum(o.itemAmount), 0),
                               coalesce(sum(o.serviceFee), 0),
                               coalesce(sum(o.deliveryMarkup), 0)
                        from Order o
                        where o.paymentStatus = :confirmed
                        group by o.currency
                        """, Object[].class)
                .setParameter("confirmed", PaymentStatus.CONFIRMED)
                .getResultList();
        Map<String, AdminDashboardResponse.OrderValue> result = new LinkedHashMap<>();
        rows.forEach(row -> result.put(
                row[0].toString(),
                new AdminDashboardResponse.OrderValue(
                        money(row[1]),
                        money(row[2]),
                        money(row[3]),
                        money(row[4])
                )
        ));
        return Map.copyOf(result);
    }

    public Map<String, BigDecimal> availableWalletBalances() {
        return groupedMoney("select w.currency, coalesce(sum(w.balance), 0) from Wallet w group by w.currency");
    }

    public Map<String, BigDecimal> outstandingWithdrawalAmounts() {
        return groupedMoney(entityManager.createQuery("""
                        select w.currency, coalesce(sum(w.amount), 0)
                        from WalletWithdrawal w
                        where w.status in :statuses
                        group by w.currency
                        """, Object[].class)
                .setParameter("statuses", List.of(
                        WalletWithdrawalStatus.PENDING,
                        WalletWithdrawalStatus.DISPATCH_UNKNOWN,
                        WalletWithdrawalStatus.REQUIRES_ACTION
                ))
                .getResultList());
    }

    public Map<String, BigDecimal> unsettledOrderPrincipal() {
        return groupedMoney(entityManager.createQuery("""
                        select o.currency, coalesce(sum(o.itemAmount), 0)
                        from Order o
                        where o.paymentStatus = :confirmed and o.settlementStatus <> :completed
                        group by o.currency
                        """, Object[].class)
                .setParameter("confirmed", PaymentStatus.CONFIRMED)
                .setParameter("completed", SettlementStatus.COMPLETED)
                .getResultList());
    }

    public Map<String, Long> withdrawalStatusCounts() {
        return groupedCounts("select w.status, count(w) from WalletWithdrawal w group by w.status");
    }

    public Map<String, Long> settlementStatusCounts() {
        return groupedCounts("select s.status, count(s) from OrderSettlement s group by s.status");
    }

    public Map<String, Long> shipmentStatusCounts() {
        return groupedCounts("select s.status, count(s) from OrderShipment s group by s.status");
    }

    public Map<String, Long> paymentReceiptClassificationCounts() {
        return groupedCounts("select r.classification, count(r) from PaymentReceipt r group by r.classification");
    }

    public Map<String, Map<String, BigDecimal>> paymentReceiptAmountsRequiringReview() {
        List<Object[]> rows = entityManager.createQuery("""
                        select r.classification, r.currency, coalesce(sum(r.amount), 0)
                        from PaymentReceipt r
                        where r.providerStatus = :success and r.classification in :classifications
                        group by r.classification, r.currency
                        """, Object[].class)
                .setParameter("success", PaymentTransactionStatus.SUCCESS)
                .setParameter("classifications", List.of(
                        PaymentReceiptClassification.UNDERPAID,
                        PaymentReceiptClassification.OVERPAID,
                        PaymentReceiptClassification.LATE,
                        PaymentReceiptClassification.DUPLICATE,
                        PaymentReceiptClassification.UNMATCHED
                ))
                .getResultList();
        Map<String, Map<String, BigDecimal>> result = new LinkedHashMap<>();
        rows.forEach(row -> result
                .computeIfAbsent(row[0].toString(), ignored -> new LinkedHashMap<>())
                .put(row[1] == null ? "UNKNOWN" : row[1].toString(), money(row[2])));
        result.replaceAll((classification, amounts) -> Map.copyOf(amounts));
        return Map.copyOf(result);
    }

    private long count(String query) {
        return entityManager.createQuery(query, Long.class).getSingleResult();
    }

    private Map<String, Long> groupedCounts(String query) {
        List<Object[]> rows = entityManager.createQuery(query, Object[].class).getResultList();
        Map<String, Long> result = new LinkedHashMap<>();
        rows.forEach(row -> result.put(row[0].toString(), ((Number) row[1]).longValue()));
        return Map.copyOf(result);
    }

    private Map<String, BigDecimal> groupedMoney(String query) {
        return groupedMoney(entityManager.createQuery(query, Object[].class).getResultList());
    }

    private Map<String, BigDecimal> groupedMoney(List<Object[]> rows) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        rows.forEach(row -> result.put(row[0].toString(), money(row[1])));
        return Map.copyOf(result);
    }

    private BigDecimal money(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }
}
