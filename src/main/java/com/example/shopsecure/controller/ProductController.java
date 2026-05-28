package com.example.shopsecure.controller;

import com.example.shopsecure.dto.request.CreateProductRequest;
import com.example.shopsecure.dto.response.ProductResponse;
import com.example.shopsecure.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/vendor/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ProductResponse> createProduct(
            @Valid @ModelAttribute CreateProductRequest request,
            @RequestPart(value = "images", required = false) List<MultipartFile> images) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(productService.createProduct(request, images));
    }

    @GetMapping
    public ResponseEntity<List<ProductResponse>> getMyProducts() {
        return ResponseEntity.ok(productService.getMyProducts());
    }

    @GetMapping("/{productId}")
    public ResponseEntity<ProductResponse> getProductById(@PathVariable Long productId) {
        return ResponseEntity.ok(productService.getProductById(productId));
    }

    @PutMapping(value = "/{productId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ProductResponse> updateProduct(
            @PathVariable Long productId,
            @Valid @ModelAttribute CreateProductRequest request,
            @RequestPart(value = "images", required = false) List<MultipartFile> images) {

        return ResponseEntity.ok(productService.updateProduct(productId, request, images));
    }

    @DeleteMapping("/{productId}")
    public ResponseEntity<Void> deleteProduct(@PathVariable Long productId) {
        productService.deleteProduct(productId);
        return ResponseEntity.noContent().build();
    }
}