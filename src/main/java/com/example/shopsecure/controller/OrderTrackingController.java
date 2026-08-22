package com.example.shopsecure.controller;

import com.example.shopsecure.dto.response.PublicOrderTrackingResponse;
import com.example.shopsecure.service.OrderTrackingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders/tracking")
@RequiredArgsConstructor
public class OrderTrackingController {

    private final OrderTrackingService trackingService;

    @GetMapping("/{trackingToken}")
    public PublicOrderTrackingResponse getTracking(@PathVariable String trackingToken) {
        return trackingService.getPublicTracking(trackingToken);
    }
}
