package com.example.shopsecure.controller;

import com.example.shopsecure.dto.response.ReportResponse;
import com.example.shopsecure.service.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/vendor/reports")
@RequiredArgsConstructor
@Slf4j
public class ReportController {

    private final ReportService reportService;

    @GetMapping("/dashboard")
    public ResponseEntity<ReportResponse.DashboardSummary> getDashboardSummary() {
        log.info("Fetching dashboard summary");
        return ResponseEntity.ok(reportService.getDashboardSummary());
    }

    @GetMapping("/profit-loss")
    public ResponseEntity<ReportResponse.ProfitLossReport> getProfitLossReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        log.info("Fetching profit/loss report: start={}, end={}", startDate, endDate);
        return ResponseEntity.ok(reportService.getProfitLossReport(startDate, endDate));
    }

    @GetMapping("/sales")
    public ResponseEntity<ReportResponse.SalesReport> getSalesReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        log.info("Fetching sales report: start={}, end={}", startDate, endDate);
        return ResponseEntity.ok(reportService.getSalesReport(startDate, endDate));
    }

    @GetMapping("/expenses")
    public ResponseEntity<ReportResponse.ExpenseReport> getExpenseReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        log.info("Fetching expense report: start={}, end={}", startDate, endDate);
        return ResponseEntity.ok(reportService.getExpenseReport(startDate, endDate));
    }
}
