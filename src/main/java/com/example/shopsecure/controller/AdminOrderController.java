package com.example.shopsecure.controller;

import com.example.shopsecure.dto.request.CreateShippingRateRequest;
import com.example.shopsecure.dto.request.UpdateOrderStatusRequest;
import com.example.shopsecure.dto.request.UpdateShippingFeeRequest;
import com.example.shopsecure.dto.response.OrderResponse;
import com.example.shopsecure.dto.response.ShippingAreaResponse;
import com.example.shopsecure.service.AdminOrderService;
import com.example.shopsecure.service.ShippingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/orders")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Slf4j
public class AdminOrderController {

    private final AdminOrderService adminOrderService;
    private final ShippingService shippingService;

    @GetMapping("/orders")
    public ResponseEntity<Page<OrderResponse>> getAllOrders(
            @RequestParam(defaultValue = "0") int page) {
        log.info("Admin fetching all orders: page={}", page);
        return ResponseEntity.ok(adminOrderService.getAllOrders(page));
    }

    @GetMapping("/orders/pending")
    public ResponseEntity<Page<OrderResponse>> getPendingOrders(
            @RequestParam(defaultValue = "0") int page) {
        log.info("Admin fetching pending orders: page={}", page);
        return ResponseEntity.ok(adminOrderService.getPendingOrders(page));
    }

    @GetMapping("/{orderNumber}")
    public OrderResponse getOrderByOrderNumber(
            @PathVariable String orderNumber
    ) {
        log.info("Admin fetching order: orderNumber={}", orderNumber);
        return adminOrderService
                .getOrderByOrderNumber(orderNumber);
    }

    @GetMapping("/business/{businessId}")
    public List<OrderResponse> getOrdersByBusiness(
            @PathVariable Long businessId
    ) {
        log.info("Admin fetching orders by business: businessId={}", businessId);
        return adminOrderService
                .getOrdersByBusiness(businessId);
    }

    @GetMapping("/search")
    public List<OrderResponse> searchBusiness(
            @RequestParam String businessName
    ) {
        log.info("Admin searching orders by business name: businessName={}", businessName);
        return adminOrderService
                .searchByBusinessName(businessName);
    }

    @PutMapping("/{orderId}/status")
    public OrderResponse updateOrderStatus(
            @PathVariable Long orderId,
            @RequestBody UpdateOrderStatusRequest request
    ) {
        log.info("Admin updating order status: orderId={}, newStatus={}", orderId, request.getStatus());
        return adminOrderService
                .updateOrderStatus(orderId, request);
    }

    @PostMapping("/create/shipping")
    public ShippingAreaResponse createShippingRate(
            @RequestBody CreateShippingRateRequest request) {

        log.info("Admin creating shipping rate: area={}", request.getArea());
        return
                shippingService.createShippingRate(request);
    }


    @PostMapping("/create/shipping/list")
    public List<ShippingAreaResponse> createShippingRate(
            @RequestBody List<CreateShippingRateRequest> requests) {
        log.info("Admin creating {} shipping rates", requests.size());
        return shippingService.createShippingRates(requests);
    }

    @PatchMapping("/{id}/fee")
    public ShippingAreaResponse updateShippingFee(
            @PathVariable Long id,
            @RequestBody UpdateShippingFeeRequest request) {

        log.info("Admin updating shipping fee: id={}", id);
        return shippingService.updateShippingFee(id, request);
    }


}