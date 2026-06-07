package com.example.shopsecure.controller;

import com.example.shopsecure.dto.request.UpdateOrderStatusRequest;
import com.example.shopsecure.dto.response.OrderResponse;
import com.example.shopsecure.service.AdminOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/orders")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminOrderController {

    private final AdminOrderService adminOrderService;


    @GetMapping("/orders")
    public ResponseEntity<Page<OrderResponse>> getAllOrders(
            @RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok(adminOrderService.getAllOrders(page));
    }

    @GetMapping("/orders/pending")
    public ResponseEntity<Page<OrderResponse>> getPendingOrders(
            @RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok(adminOrderService.getPendingOrders(page));
    }

    @GetMapping("/{orderNumber}")
    public OrderResponse getOrderByOrderNumber(
            @PathVariable String orderNumber
    ) {
        return adminOrderService
                .getOrderByOrderNumber(orderNumber);
    }

    @GetMapping("/business/{businessId}")
    public List<OrderResponse> getOrdersByBusiness(
            @PathVariable Long businessId
    ) {
        return adminOrderService
                .getOrdersByBusiness(businessId);
    }

    @GetMapping("/search")
    public List<OrderResponse> searchBusiness(
            @RequestParam String businessName
    ) {
        return adminOrderService
                .searchByBusinessName(businessName);
    }

    @PutMapping("/{orderId}/status")
    public OrderResponse updateOrderStatus(
            @PathVariable Long orderId,
            @RequestBody UpdateOrderStatusRequest request
    ) {
        return adminOrderService
                .updateOrderStatus(orderId, request);
    }
}