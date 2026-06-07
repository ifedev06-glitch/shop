package com.example.shopsecure.controller;

import com.example.shopsecure.dto.request.InitializePaymentRequest;
import com.example.shopsecure.dto.request.PaystackWebhookRequest;
import com.example.shopsecure.dto.response.InitializePaymentResponse;
import com.example.shopsecure.dto.response.TransactionResponse;
import com.example.shopsecure.service.PaymentService;
import com.example.shopsecure.service.PaystackService;
import com.example.shopsecure.service.TransactionService;
import com.example.shopsecure.service.WithdrawalService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final WithdrawalService withdrawalService;
    private final PaystackService paystackService;
    private final ObjectMapper objectMapper;
    private final TransactionService transactionService;

    @PostMapping("/initialize")
    public ResponseEntity<InitializePaymentResponse> initializePayment(
            @RequestBody InitializePaymentRequest request
    ) {
        return ResponseEntity.ok(
                paymentService.initializePayment(request));
    }

    @GetMapping("/transactions")
    public ResponseEntity<Page<TransactionResponse>> getTransactions(
            @RequestParam(defaultValue = "0") int page
    ) {
        return ResponseEntity.ok(transactionService.getTransactionHistory(page, 10));
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> handleWebhook(
            @RequestBody String payload,
            @RequestHeader("x-paystack-signature") String signature
    ) throws Exception {

        PaystackWebhookRequest webhookRequest =
                objectMapper.readValue(payload, PaystackWebhookRequest.class);

        String event = webhookRequest.getEvent();

        switch (event) {
            case "charge.success" ->
                    paymentService.handleWebhook(payload, signature, webhookRequest);
            case "transfer.success" -> {
                if (!paystackService.verifyWebhookSignature(payload, signature))
                    throw new RuntimeException("Invalid webhook signature");
                withdrawalService.handleTransferSuccess(
                        webhookRequest.getData().getTransferCode()
                );
            }

            case "transfer.failed", "transfer.reversed" -> {
                if (!paystackService.verifyWebhookSignature(payload, signature))
                    throw new RuntimeException("Invalid webhook signature");
                withdrawalService.handleTransferFailed(
                        webhookRequest.getData().getTransferCode(),
                        webhookRequest.getData().getReason()
                );
            }

            default -> { /* ignore unhandled events */ }
        }

        return ResponseEntity.ok().build();
    }
}