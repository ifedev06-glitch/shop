package com.example.shopsecure.service;

import com.example.shopsecure.dto.request.InitializePaymentRequest;
import com.example.shopsecure.dto.request.PaystackWebhookRequest;
import com.example.shopsecure.dto.response.InitializePaymentResponse;
import com.example.shopsecure.dto.response.OrderResponse;
import com.example.shopsecure.entity.Payment;
import com.example.shopsecure.entity.PaymentStatus;
import com.example.shopsecure.exception.PaymentException;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentTransactionService paymentTransactionService;
    private final PaymentRepository paymentRepository;
    private final PaystackService paystackService;
    private final EmailService emailService;
    private final EmailTemplateService emailTemplateService;
    private final CheckoutService checkoutService;

    public InitializePaymentResponse initializePayment(
            InitializePaymentRequest request
    ) {
        PaymentTransactionService.PaymentInitData data = paymentTransactionService.initializePaymentDb(request);

        Map<String, Object> paystackData =
                paystackService.initializeTransaction(
                        data.email(),
                        data.amount(),
                        data.reference()
                );

        return InitializePaymentResponse.builder()
                .authorizationUrl(
                        (String) paystackData.get("authorization_url"))
                .reference(data.reference())
                .orderNumber(data.orderNumber())
                .build();
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderByReference(String reference) {
        Payment payment = paymentRepository.findByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));

        log.info("Payment verified: reference={}, orderNumber={}, status={}",
                reference, payment.getOrder().getOrderNumber(), payment.getStatus());

        return checkoutService.getOrderByOrderNumber(payment.getOrder().getOrderNumber());
    }

    public void handleWebhook(
            String payload,
            String paystackSignature,
            PaystackWebhookRequest webhookRequest
    ) {
        if (!paystackService.verifyWebhookSignature(
                payload, paystackSignature)) {
            log.warn("Paystack webhook signature verification failed");
            throw new PaymentException("Invalid webhook signature");
        }

        String event = webhookRequest.getEvent();
        log.info("Paystack webhook received: event={}, reference={}",
                event, webhookRequest.getData() != null ? webhookRequest.getData().getReference() : null);

        if (!"charge.success".equals(event)) {
            return;
        }

        // DB work in transaction (payment, order, wallet, stock deduction)
        PaymentTransactionService.WebhookResult result = paymentTransactionService.processWebhookPayment(webhookRequest);

        if (result == null) {
            return;
        }

        log.info("Payment processed successfully: orderNumber={}, reference={}, amount={}",
                result.orderNumber(), webhookRequest.getData().getReference(), result.totalAmount());

        // Emails outside transaction
        try {
            String customerHtml = emailTemplateService.customerOrderConfirmationBody(
                    result.customerName(),
                    result.orderNumber(),
                    "₦" + result.totalAmount().toPlainString()
            );
            emailService.sendEmail(
                    result.email(),
                    "Order Confirmed - " + result.orderNumber(),
                    customerHtml
            );
            log.info("Customer confirmation email sent: orderNumber={}, email={}", result.orderNumber(), result.email());
        } catch (Exception e) {
            log.warn("Failed to send customer confirmation email: orderNumber={}, error={}",
                    result.orderNumber(), e.getMessage());
        }

        try {
            String sellerHtml = emailTemplateService.sellerNewOrderBody(
                    result.customerName(),
                    result.orderNumber(),
                    "₦" + result.totalAmount().toPlainString()
            );
            emailService.sendEmail(
                    result.sellerEmail(),
                    "New Order Received - " + result.orderNumber(),
                    sellerHtml
            );
            log.info("Vendor notification email sent: orderNumber={}, sellerEmail={}", result.orderNumber(), result.sellerEmail());
        } catch (Exception e) {
            log.warn("Failed to send vendor notification email: orderNumber={}, error={}",
                    result.orderNumber(), e.getMessage());
        }
    }

}