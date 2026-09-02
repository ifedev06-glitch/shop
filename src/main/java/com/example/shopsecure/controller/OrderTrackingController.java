package com.example.shopsecure.controller;

import com.example.shopsecure.dto.request.ConfirmDeliveryRequest;
import com.example.shopsecure.dto.response.DeliveryConfirmationResponse;
import com.example.shopsecure.dto.response.PublicOrderTrackingResponse;
import com.example.shopsecure.service.OrderSettlementService;
import com.example.shopsecure.service.OrderTrackingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders/tracking")
@RequiredArgsConstructor
public class OrderTrackingController {

    private final OrderTrackingService trackingService;
    private final OrderSettlementService settlementService;

    @GetMapping("/{trackingToken}")
    public PublicOrderTrackingResponse getTracking(@PathVariable String trackingToken) {
        return trackingService.getPublicTracking(trackingToken);
    }

    @PostMapping("/{trackingToken}/confirm-delivery")
    public DeliveryConfirmationResponse confirmDelivery(
            @PathVariable String trackingToken,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ConfirmDeliveryRequest request
    ) {
        Long orderId = trackingService.requireActiveOrderId(trackingToken);
        return settlementService.confirmPublicDelivery(orderId, idempotencyKey);
    }
}
