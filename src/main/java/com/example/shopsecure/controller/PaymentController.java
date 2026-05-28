package com.example.shopsecure.controller;

import com.example.shopsecure.dto.request.InitializePaymentRequest;
import com.example.shopsecure.dto.request.PaystackWebhookRequest;
import com.example.shopsecure.dto.response.InitializePaymentResponse;
import com.example.shopsecure.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/initialize")
    public ResponseEntity<InitializePaymentResponse> initializePayment(
            @RequestBody InitializePaymentRequest request
    ) {
        return ResponseEntity.ok(
                paymentService.initializePayment(request));
    }

    // Paystack hits this — must be publicly accessible (no auth)
    @PostMapping("/webhook")
    public ResponseEntity<Void> handleWebhook(
            @RequestBody String payload,
            @RequestHeader("x-paystack-signature") String signature
    ) {
        PaystackWebhookRequest webhookRequest =
                new ObjectMapper().readValue(
                        payload, PaystackWebhookRequest.class);

        paymentService.handleWebhook(payload, signature, webhookRequest);
        return ResponseEntity.ok().build();
    }
}
