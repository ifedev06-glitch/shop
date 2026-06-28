package com.example.shopsecure.controller;

import com.example.shopsecure.dto.response.OrderResponse;
import com.example.shopsecure.service.OrderService;
import com.example.shopsecure.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
@Slf4j
public class OrderController {

    private final OrderService orderService;
    private final PaymentService paymentService;

    @GetMapping("/history")
    public ResponseEntity<Page<OrderResponse>> getOrderHistory(
            @RequestParam(defaultValue = "0") int page
    ) {
        log.info("Fetching order history: page={}", page);
        return ResponseEntity.ok(orderService.getOrderHistory(page));
    }


    @GetMapping("/payment/verify/{reference}")
    public ResponseEntity<OrderResponse> getOrderByReference(@PathVariable String reference) {
        log.info("Payment verification request: reference={}", reference);
        return ResponseEntity.ok(paymentService.getOrderByReference(reference));
    }

}
