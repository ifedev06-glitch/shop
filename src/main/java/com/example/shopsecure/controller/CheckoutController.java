package com.example.shopsecure.controller;

import com.example.shopsecure.dto.request.CheckoutRequest;
import com.example.shopsecure.dto.request.CheckoutSummaryRequest;
import com.example.shopsecure.dto.response.CheckoutSummaryResponse;
import com.example.shopsecure.dto.response.OrderResponse;
import com.example.shopsecure.service.CartService;
import com.example.shopsecure.service.CheckoutService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/checkout")
@RequiredArgsConstructor
public class CheckoutController {

    private final CheckoutService checkoutService;
    private final CartService cartService;


    @PostMapping("/calculate")
    public CheckoutSummaryResponse calculateCheckout(
            @RequestBody CheckoutSummaryRequest request
    ) {
        return checkoutService.calculateCheckout(request);
    }

    @PostMapping
    public OrderResponse checkout(
            @RequestBody CheckoutRequest request
    ) {
        return checkoutService.checkout(request);
    }

    @GetMapping("/{orderNumber}")
    public OrderResponse getOrder(
            @PathVariable String orderNumber
    ) {
        return checkoutService.getOrderByOrderNumber(orderNumber);
    }


}
