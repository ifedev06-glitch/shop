package com.example.shopsecure.controller;

import com.example.shopsecure.dto.request.CreateOrderDisputeRequest;
import com.example.shopsecure.dto.response.MessageResponse;
import com.example.shopsecure.service.OrderDisputeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderDisputeController {

    private static final String ACCEPTED_MESSAGE =
            "If the order is eligible, the dispute has been received and the ShopSecure team will contact both parties.";

    private final OrderDisputeService disputeService;

    @PostMapping("/{orderId}/disputes")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse submit(
            @PathVariable Long orderId,
            @Valid @RequestBody CreateOrderDisputeRequest request
    ) {
        disputeService.submit(orderId, request.reason());
        return new MessageResponse(ACCEPTED_MESSAGE);
    }
}
