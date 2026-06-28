package com.example.shopsecure.controller;

import com.example.shopsecure.dto.request.CheckoutRequest;
import com.example.shopsecure.dto.request.CheckoutSummaryRequest;
import com.example.shopsecure.dto.request.UpdateOrderStatusRequest;
import com.example.shopsecure.dto.response.CheckoutSummaryResponse;
import com.example.shopsecure.dto.response.OrderResponse;
import com.example.shopsecure.service.CartService;
import com.example.shopsecure.service.CheckoutService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/checkout")
@RequiredArgsConstructor
@Slf4j
public class CheckoutController {

    private final CheckoutService checkoutService;
    private final CartService cartService;


    @PostMapping("/calculate")
    public CheckoutSummaryResponse calculateCheckout(
            @RequestBody CheckoutSummaryRequest request
    ) {
        log.info("Checkout calculation: sessionId={}, businessId={}, shippingArea={}",
                request.getSessionId(), request.getBusinessId(), request.getShippingArea());
        return checkoutService.calculateCheckout(request);
    }

    @PostMapping
    public OrderResponse checkout(
            @RequestBody CheckoutRequest request
    ) {
        log.info("Checkout started: sessionId={}, businessId={}, customerEmail={}",
                request.getSessionId(), request.getBusinessId(), request.getEmail());
        return checkoutService.checkout(request);
    }

    @GetMapping("/{orderNumber}")
    public OrderResponse getOrder(
            @PathVariable String orderNumber
    ) {
        log.info("Fetching order: orderNumber={}", orderNumber);
        return checkoutService.getOrderByOrderNumber(orderNumber);
    }


    @PutMapping("/{orderId}/status")
    public OrderResponse orderReadyForDelivery(
            @PathVariable Long orderId,
            @RequestBody UpdateOrderStatusRequest request
    ) {
        log.info("Updating order status: orderId={}, newStatus={}", orderId, request.getStatus());
        return checkoutService
                .orderReadyForDelivery(orderId, request);
    }


}
