package com.example.shopsecure.controller;

import com.example.shopsecure.dto.request.SaleRequest;
import com.example.shopsecure.dto.response.SaleResponse;
import com.example.shopsecure.service.SaleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/vendor/sales")
@RequiredArgsConstructor
@Slf4j
public class SaleController {

    private final SaleService saleService;

    @PostMapping
    public ResponseEntity<SaleResponse> createSale(@Valid @RequestBody SaleRequest request) {
        log.info("Create sale request with {} items", request.getItems() != null ? request.getItems().size() : 0);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(saleService.createSale(request));
    }

    @GetMapping
    public ResponseEntity<List<SaleResponse>> getMySales() {
        log.info("Fetching vendor's sales");
        return ResponseEntity.ok(saleService.getMySales());
    }

    @GetMapping("/paged")
    public ResponseEntity<Page<SaleResponse>> getMySalesPaged(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        log.info("Fetching vendor's sales paged: page={}, size={}", page, size);
        return ResponseEntity.ok(saleService.getMySalesPaged(page, size));
    }

    @GetMapping("/{saleId}")
    public ResponseEntity<SaleResponse> getSaleById(@PathVariable Long saleId) {
        log.info("Fetching sale by id: saleId={}", saleId);
        return ResponseEntity.ok(saleService.getSaleById(saleId));
    }

    @DeleteMapping("/{saleId}")
    public ResponseEntity<Void> deleteSale(@PathVariable Long saleId) {
        log.info("Delete sale request: saleId={}", saleId);
        saleService.deleteSale(saleId);
        return ResponseEntity.noContent().build();
    }
}
