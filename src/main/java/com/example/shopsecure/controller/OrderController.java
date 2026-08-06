package com.example.shopsecure.controller;

import com.example.shopsecure.dto.request.CreateOrderRequest;
import com.example.shopsecure.dto.request.VendorInfoRequest;
import com.example.shopsecure.dto.request.VerifyOrderOtpRequest;
import com.example.shopsecure.dto.response.CreateOrderResponse;
import com.example.shopsecure.dto.response.OrderDetailsResponse;
import com.example.shopsecure.dto.response.OrderStatusHistoryResponse;
import com.example.shopsecure.dto.response.OrderVerificationResponse;
import com.example.shopsecure.dto.response.PublicOrderResponse;
import com.example.shopsecure.dto.response.WalletPaymentResponse;
import com.example.shopsecure.service.OrderPaymentService;
import com.example.shopsecure.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final OrderPaymentService orderPaymentService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public CreateOrderResponse createOrder(@Valid @RequestBody CreateOrderRequest request) {
        return orderService.createOrder(request);
    }

    @GetMapping("/token/{shareToken}")
    public PublicOrderResponse getByShareToken(@PathVariable String shareToken) {
        return orderService.getByShareToken(shareToken);
    }

    @PostMapping("/token/{shareToken}/vendor-info")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public OrderVerificationResponse submitVendorInfo(
            @PathVariable String shareToken,
            @Valid @RequestBody VendorInfoRequest request
    ) {
        return orderService.submitVendorInfo(shareToken, request);
    }

    @PostMapping("/token/{shareToken}/verify-otp")
    public OrderVerificationResponse verifyVendorOtp(
            @PathVariable String shareToken,
            @Valid @RequestBody VerifyOrderOtpRequest request
    ) {
        return orderService.verifyVendorOtp(shareToken, request.code());
    }

    @GetMapping("/{orderId}")
    public OrderDetailsResponse getOrder(@PathVariable Long orderId) {
        return orderService.getOrder(orderId);
    }

    @PostMapping("/{orderId}/pay")
    public WalletPaymentResponse pay(
            @PathVariable Long orderId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return orderPaymentService.pay(orderId, idempotencyKey);
    }

    @GetMapping("/{orderId}/history")
    public List<OrderStatusHistoryResponse> getHistory(@PathVariable Long orderId) {
        return orderService.getHistory(orderId);
    }
}
