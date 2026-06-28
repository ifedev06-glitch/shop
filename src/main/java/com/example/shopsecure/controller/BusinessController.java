package com.example.shopsecure.controller;

import com.example.shopsecure.dto.response.BusinessResponse;
import com.example.shopsecure.dto.request.CreateBusinessRequest;
import com.example.shopsecure.dto.response.OrderResponse;
import com.example.shopsecure.service.BusinessService;
import com.example.shopsecure.service.CartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/vendor/business")
@RequiredArgsConstructor
@Slf4j
public class BusinessController {

    private final BusinessService businessService;
    private final CartService cartService;

    @PostMapping
    public ResponseEntity<BusinessResponse> createBusiness(
            @Valid @RequestBody CreateBusinessRequest request) {

        log.info("Business creation request: businessName={}", request.getBusinessName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(businessService.createBusiness(request));
    }

    @GetMapping
    public ResponseEntity<BusinessResponse> getBusiness() {
        log.info("Fetching current business profile");
        return ResponseEntity.ok(businessService.getBusiness());
    }

    @GetMapping("/businesses/orders/pending")
    public ResponseEntity<List<OrderResponse>> getPendingOrders() {
        log.info("Fetching pending orders for vendor");
        return ResponseEntity.ok(cartService.getPendingOrders());
    }

}