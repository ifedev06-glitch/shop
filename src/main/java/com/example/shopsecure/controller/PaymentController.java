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
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Slf4j
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
        log.info("Payment initialization: orderNumber={}", request.getOrderNumber());
        return ResponseEntity.ok(
                paymentService.initializePayment(request));
    }

    @GetMapping("/transactions")
    public ResponseEntity<Page<TransactionResponse>> getTransactions(
            @RequestParam(defaultValue = "0") int page
    ) {
        log.info("Fetching transaction history: page={}", page);
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
        log.info("Payment webhook received: event={}", event);

        switch (event) {
            case "charge.success" -> {
                log.info("Processing charge.success webhook");
                paymentService.handleWebhook(payload, signature, webhookRequest);
            }
            case "transfer.success" -> {
                log.info("Processing transfer.success webhook: transferCode={}",
                        webhookRequest.getData().getTransferCode());
                if (!paystackService.verifyWebhookSignature(payload, signature)) {
                    log.warn("Invalid webhook signature for transfer.success");
                    throw new RuntimeException("Invalid webhook signature");
                }
                withdrawalService.handleTransferSuccess(
                        webhookRequest.getData().getTransferCode()
                );
            }

            case "transfer.failed", "transfer.reversed" -> {
                log.warn("Processing {} webhook: transferCode={}",
                        event, webhookRequest.getData().getTransferCode());
                if (!paystackService.verifyWebhookSignature(payload, signature)) {
                    log.warn("Invalid webhook signature for {}", event);
                    throw new RuntimeException("Invalid webhook signature");
                }
                withdrawalService.handleTransferFailed(
                        webhookRequest.getData().getTransferCode(),
                        webhookRequest.getData().getReason()
                );
            }

            default -> log.info("Ignoring unhandled webhook event: {}", event);
        }

        return ResponseEntity.ok().build();
    }
}