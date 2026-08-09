package com.example.shopsecure.controller;

import com.example.shopsecure.dto.request.*;
import com.example.shopsecure.dto.response.*;
import com.example.shopsecure.service.*;
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
    private final OrderShippingService shippingService;
    private final OrderSettlementService settlementService;
    private final OrderWalletFundingService walletFundingService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public CreateOrderResponse createOrder(@Valid @RequestBody CreateOrderRequest request) {
        return orderService.createOrder(request);
    }

    @GetMapping
    public List<OrderDetailsResponse> getOrders() {
        return orderService.getOrders();
    }

    @GetMapping("/{orderId}")
    public OrderDetailsResponse getOrder(@PathVariable Long orderId) {
        return orderService.getOrder(orderId);
    }

    @GetMapping("/{orderId}/history")
    public List<OrderStatusHistoryResponse> getHistory(@PathVariable Long orderId) {
        return orderService.getHistory(orderId);
    }

    @PostMapping("/{orderId}/shipping/quotes")
    public List<ShippingQuoteResponse> createShippingQuotes(@PathVariable Long orderId) {
        return shippingService.createCreatorQuotes(orderId);
    }

    @PutMapping("/{orderId}/shipping/selection")
    public ShippingSelectionResponse selectShippingQuote(
            @PathVariable Long orderId,
            @Valid @RequestBody SelectShippingQuoteRequest request
    ) {
        return shippingService.selectCreatorQuote(orderId, request.quoteId());
    }

    @PostMapping("/{orderId}/payments/wallet")
    @PreAuthorize("isAuthenticated()")
    public OrderWalletPaymentResponse payFromWallet(
            @PathVariable Long orderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return walletFundingService.pay(orderId, idempotencyKey);
    }

    @GetMapping("/{orderId}/shipping/tracking")
    public ShippingStatusResponse refreshTracking(@PathVariable Long orderId) {
        return shippingService.refreshTracking(orderId);
    }

    @PostMapping("/{orderId}/shipping/cancel")
    public ShippingStatusResponse cancelBeforePayment(@PathVariable Long orderId) {
        return shippingService.cancelBeforePayment(orderId);
    }

    @PostMapping("/{orderId}/confirm-delivery")
    public DeliveryConfirmationResponse confirmDelivery(
            @PathVariable Long orderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ConfirmDeliveryRequest request
    ) {
        return settlementService.confirmCreatorDelivery(orderId, idempotencyKey);
    }
}
