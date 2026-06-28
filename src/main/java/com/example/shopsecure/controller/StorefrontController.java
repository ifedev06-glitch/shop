package com.example.shopsecure.controller;

import com.example.shopsecure.dto.response.BusinessResponse;
import com.example.shopsecure.dto.response.CategoryResponse;
import com.example.shopsecure.dto.response.ProductResponse;
import com.example.shopsecure.service.StorefrontService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/storefront")
@RequiredArgsConstructor
@Slf4j
public class StorefrontController {

    private final StorefrontService storefrontService;

    @GetMapping("/{slug}")
    public ResponseEntity<BusinessResponse> getStorefront(@PathVariable String slug) {
        log.info("Storefront view: slug={}", slug);
        return ResponseEntity.ok(storefrontService.getStorefront(slug));
    }

    @GetMapping("/{slug}/products")
    public ResponseEntity<List<ProductResponse>> getStoreProducts(@PathVariable String slug) {
        log.info("Storefront products list: slug={}", slug);
        return ResponseEntity.ok(storefrontService.getStoreProducts(slug));
    }

    @GetMapping("/{slug}/products/{productId}")
    public ResponseEntity<ProductResponse> getStoreProduct(
            @PathVariable String slug,
            @PathVariable Long productId) {
        log.info("Storefront product view: slug={}, productId={}", slug, productId);
        return ResponseEntity.ok(storefrontService.getStoreProduct(slug, productId));
    }

    @GetMapping("/{slug}/categories")
    public ResponseEntity<List<CategoryResponse>> getStoreCategories(@PathVariable String slug) {
        log.info("Storefront categories: slug={}", slug);
        return ResponseEntity.ok(storefrontService.getStoreCategories(slug));
    }

    @GetMapping("/{slug}/products/category/{categoryId}")
    public ResponseEntity<List<ProductResponse>> getStoreProductsByCategory(
            @PathVariable String slug,
            @PathVariable Long categoryId) {
        log.info("Storefront products by category: slug={}, categoryId={}", slug, categoryId);
        return ResponseEntity.ok(storefrontService.getStoreProductsByCategory(slug, categoryId));
    }
}