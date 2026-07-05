package com.example.shopsecure.service;

import com.example.shopsecure.dto.response.ReportResponse;
import com.example.shopsecure.dto.response.SaleResponse;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportService {

    private final SaleRepository saleRepository;
    private final SaleItemRepository saleItemRepository;
    private final ExpenseRepository expenseRepository;
    private final ProductRepository productRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;

    public ReportResponse.DashboardSummary getDashboardSummary() {
        Business business = getCurrentBusiness();

        LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        LocalDateTime now = LocalDateTime.now();

        List<Sale> monthSales = saleRepository
                .findByBusiness_IdAndCreatedAtBetweenOrderByCreatedAtDesc(business.getId(), monthStart, now);

        long totalSalesCount = monthSales.size();
        BigDecimal totalRevenue = monthSales.stream()
                .map(Sale::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Expense> monthExpenses = expenseRepository
                .findByBusiness_IdAndCreatedAtBetweenOrderByCreatedAtDesc(business.getId(), monthStart, now);

        long totalExpensesCount = monthExpenses.size();
        BigDecimal totalExpensesAmount = monthExpenses.stream()
                .map(Expense::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Sale> recentSales = saleRepository
                .findByBusiness_IdOrderByCreatedAtDesc(business.getId())
                .stream()
                .limit(10)
                .toList();

        List<Product> lowStockProducts = productRepository.findLowStockByBusiness(business.getId());
        long lowStockCount = lowStockProducts.size();

        BigDecimal totalCost = BigDecimal.ZERO;
        List<SaleItem> allItems = saleItemRepository.findBySale_Business_IdOrderBySale_CreatedAtDesc(business.getId());
        for (SaleItem item : allItems) {
            totalCost = totalCost.add(item.getCostPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
        }

        BigDecimal netProfit = totalRevenue.subtract(totalCost).subtract(totalExpensesAmount);

        List<SaleResponse> recentSaleResponses = recentSales.stream()
                .map(this::toSaleResponse)
                .toList();

        return ReportResponse.DashboardSummary.builder()
                .totalSalesCount(totalSalesCount)
                .totalRevenue(totalRevenue)
                .totalExpensesCount(totalExpensesCount)
                .totalExpenses(totalExpensesAmount)
                .netProfit(netProfit)
                .lowStockCount(lowStockCount)
                .recentSales(recentSaleResponses)
                .build();
    }

    public ReportResponse.ProfitLossReport getProfitLossReport(LocalDate startDate, LocalDate endDate) {
        Business business = getCurrentBusiness();

        if (startDate == null) startDate = LocalDate.now().minusMonths(1);
        if (endDate == null) endDate = LocalDate.now();

        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.atTime(LocalTime.MAX);

        List<Sale> sales = saleRepository
                .findByBusiness_IdAndCreatedAtBetweenOrderByCreatedAtDesc(business.getId(), start, end);

        BigDecimal totalRevenue = sales.stream()
                .map(Sale::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<SaleItem> allItems = saleItemRepository
                .findBySale_Business_IdOrderBySale_CreatedAtDesc(business.getId());

        BigDecimal totalCost = allItems.stream()
                .filter(item -> {
                    LocalDateTime saleDate = item.getSale().getCreatedAt();
                    return !saleDate.isBefore(start) && !saleDate.isAfter(end);
                })
                .map(item -> item.getCostPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Expense> expenses = expenseRepository
                .findByBusiness_IdAndCreatedAtBetweenOrderByCreatedAtDesc(business.getId(), start, end);

        BigDecimal totalExpenses = expenses.stream()
                .map(Expense::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal grossProfit = totalRevenue.subtract(totalCost);
        BigDecimal netProfit = grossProfit.subtract(totalExpenses);

        return ReportResponse.ProfitLossReport.builder()
                .totalRevenue(totalRevenue)
                .totalCostOfGoodsSold(totalCost)
                .grossProfit(grossProfit)
                .totalExpenses(totalExpenses)
                .netProfit(netProfit)
                .startDate(startDate)
                .endDate(endDate)
                .build();
    }

    public ReportResponse.SalesReport getSalesReport(LocalDate startDate, LocalDate endDate) {
        Business business = getCurrentBusiness();

        if (startDate == null) startDate = LocalDate.now().minusMonths(1);
        if (endDate == null) endDate = LocalDate.now();

        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.atTime(LocalTime.MAX);

        List<Sale> sales = saleRepository
                .findByBusiness_IdAndCreatedAtBetweenOrderByCreatedAtDesc(business.getId(), start, end);

        long totalSales = sales.size();
        BigDecimal totalRevenue = sales.stream()
                .map(Sale::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Long> salesByPaymentMethod = sales.stream()
                .filter(s -> s.getPaymentMethod() != null)
                .collect(Collectors.groupingBy(
                        Sale::getPaymentMethod,
                        Collectors.counting()
                ));

        Map<Long, ReportResponse.SalesReport.ProductBreakdown> productMap = new HashMap<>();
        for (Sale sale : sales) {
            if (sale.getItems() == null) continue;
            for (SaleItem item : sale.getItems()) {
                Product product = item.getProduct();
                productMap.merge(product.getId(),
                        ReportResponse.SalesReport.ProductBreakdown.builder()
                                .productId(product.getId())
                                .productName(item.getProductName())
                                .quantitySold(item.getQuantity())
                                .revenue(item.getSubtotal())
                                .build(),
                        (existing, incoming) -> {
                            existing = ReportResponse.SalesReport.ProductBreakdown.builder()
                                    .productId(existing.getProductId())
                                    .productName(existing.getProductName())
                                    .quantitySold(existing.getQuantitySold() + incoming.getQuantitySold())
                                    .revenue(existing.getRevenue().add(incoming.getRevenue()))
                                    .build();
                            return existing;
                        });
            }
        }

        List<ReportResponse.SalesReport.ProductBreakdown> topProducts = productMap.values().stream()
                .sorted((a, b) -> b.getRevenue().compareTo(a.getRevenue()))
                .limit(20)
                .toList();

        return ReportResponse.SalesReport.builder()
                .totalSales(totalSales)
                .totalRevenue(totalRevenue)
                .startDate(startDate)
                .endDate(endDate)
                .topProducts(topProducts)
                .salesByPaymentMethod(salesByPaymentMethod)
                .build();
    }

    public ReportResponse.ExpenseReport getExpenseReport(LocalDate startDate, LocalDate endDate) {
        Business business = getCurrentBusiness();

        if (startDate == null) startDate = LocalDate.now().minusMonths(1);
        if (endDate == null) endDate = LocalDate.now();

        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.atTime(LocalTime.MAX);

        List<Expense> expenses = expenseRepository
                .findByBusiness_IdAndCreatedAtBetweenOrderByCreatedAtDesc(business.getId(), start, end);

        long totalExpenses = expenses.size();
        BigDecimal totalAmount = expenses.stream()
                .map(Expense::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, ReportResponse.ExpenseReport.ExpenseCategoryBreakdown> byCategory = expenses.stream()
                .filter(e -> e.getCategory() != null)
                .collect(Collectors.groupingBy(
                        Expense::getCategory,
                        Collectors.collectingAndThen(
                                Collectors.toList(),
                                list -> ReportResponse.ExpenseReport.ExpenseCategoryBreakdown.builder()
                                        .count(list.size())
                                        .total(list.stream()
                                                .map(Expense::getAmount)
                                                .reduce(BigDecimal.ZERO, BigDecimal::add))
                                        .build()
                        )
                ));

        return ReportResponse.ExpenseReport.builder()
                .totalExpenses(totalExpenses)
                .totalAmount(totalAmount)
                .startDate(startDate)
                .endDate(endDate)
                .byCategory(byCategory)
                .build();
    }

    private Business getCurrentBusiness() {
        String email = SecurityContextHolder.getContext()
                .getAuthentication()
                .getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return businessRepository.findByOwner_Id(user.getId())
                .orElseThrow(() -> new BusinessException("You need to create a business first"));
    }

    private SaleResponse toSaleResponse(Sale sale) {
        List<SaleResponse.ItemResponse> itemResponses = sale.getItems() != null
                ? sale.getItems().stream()
                .map(item -> SaleResponse.ItemResponse.builder()
                        .id(item.getId())
                        .productId(item.getProduct().getId())
                        .productName(item.getProductName())
                        .quantity(item.getQuantity())
                        .sellingPrice(item.getSellingPrice())
                        .costPrice(item.getCostPrice())
                        .subtotal(item.getSubtotal())
                        .build())
                .toList()
                : List.of();

        return SaleResponse.builder()
                .id(sale.getId())
                .saleNumber(sale.getSaleNumber())
                .totalAmount(sale.getTotalAmount())
                .discount(sale.getDiscount())
                .paymentMethod(sale.getPaymentMethod())
                .customerName(sale.getCustomerName())
                .customerPhone(sale.getCustomerPhone())
                .notes(sale.getNotes())
                .createdAt(sale.getCreatedAt())
                .items(itemResponses)
                .build();
    }
}
