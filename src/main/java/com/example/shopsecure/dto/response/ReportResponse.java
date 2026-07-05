package com.example.shopsecure.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Getter
@Builder
@AllArgsConstructor
public class ReportResponse {

    @Getter
    @Builder
    @AllArgsConstructor
    public static class DashboardSummary {
        private long totalSalesCount;
        private BigDecimal totalRevenue;
        private long totalExpensesCount;
        private BigDecimal totalExpenses;
        private BigDecimal netProfit;
        private long lowStockCount;
        private List<SaleResponse> recentSales;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    public static class ProfitLossReport {
        private BigDecimal totalRevenue;
        private BigDecimal totalCostOfGoodsSold;
        private BigDecimal grossProfit;
        private BigDecimal totalExpenses;
        private BigDecimal netProfit;
        private LocalDate startDate;
        private LocalDate endDate;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    public static class SalesReport {
        private long totalSales;
        private BigDecimal totalRevenue;
        private LocalDate startDate;
        private LocalDate endDate;
        private List<ProductBreakdown> topProducts;
        private Map<String, Long> salesByPaymentMethod;

        @Getter
        @Builder
        @AllArgsConstructor
        public static class ProductBreakdown {
            private Long productId;
            private String productName;
            private long quantitySold;
            private BigDecimal revenue;
        }
    }

    @Getter
    @Builder
    @AllArgsConstructor
    public static class ExpenseReport {
        private long totalExpenses;
        private BigDecimal totalAmount;
        private LocalDate startDate;
        private LocalDate endDate;
        private Map<String, ExpenseCategoryBreakdown> byCategory;

        @Getter
        @Builder
        @AllArgsConstructor
        public static class ExpenseCategoryBreakdown {
            private long count;
            private BigDecimal total;
        }
    }
}
