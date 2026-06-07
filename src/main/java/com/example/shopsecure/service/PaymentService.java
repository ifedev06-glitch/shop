package com.example.shopsecure.service;

import com.example.shopsecure.dto.request.InitializePaymentRequest;
import com.example.shopsecure.dto.request.PaystackWebhookRequest;
import com.example.shopsecure.dto.response.InitializePaymentResponse;
import com.example.shopsecure.dto.response.OrderResponse;
import com.example.shopsecure.entity.Order;
import com.example.shopsecure.entity.Payment;
import com.example.shopsecure.entity.PaymentStatus;
import com.example.shopsecure.repository.OrderRepository;
import com.example.shopsecure.repository.PaymentRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final PaystackService paystackService;
    private final WalletService walletService;
    private final EmailService emailService;
    private final EmailTemplateService emailTemplateService;
    private final CheckoutService checkoutService;

    public InitializePaymentResponse initializePayment(
            InitializePaymentRequest request
    ) {
        Order order = orderRepository
                .findByOrderNumber(request.getOrderNumber())
                .orElseThrow(() ->
                        new RuntimeException("Order not found"));

        if (order.getPaymentStatus().equals("PAID")) {
            throw new RuntimeException("Order already paid");
        }
        // Check if a pending payment already exists for this order
        Optional<Payment> existingPayment = paymentRepository
                .findByOrder_IdAndStatus(
                        order.getId(), PaymentStatus.PENDING);
        if (existingPayment.isPresent()) {
            Payment payment = existingPayment.get();
            Map<String, Object> paystackData =
                    paystackService.initializeTransaction(
                            order.getEmail(),
                            order.getTotalAmount(),
                            payment.getReference());
            return InitializePaymentResponse.builder()
                    .authorizationUrl(
                            (String) paystackData.get("authorization_url"))
                    .reference(payment.getReference())
                    .orderNumber(order.getOrderNumber())
                    .build();}
        String reference = "PAY-" + UUID.randomUUID()
                .toString()
                .substring(0, 8)
                .toUpperCase();
        Payment payment = Payment.builder()
                .order(order)
                .reference(reference)
                .amount(order.getTotalAmount())
                .status(PaymentStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();
        paymentRepository.save(payment);
        Map<String, Object> paystackData =
                paystackService.initializeTransaction(
                        order.getEmail(),
                        order.getTotalAmount(),
                        reference
                );

        return InitializePaymentResponse.builder()
                .authorizationUrl(
                        (String) paystackData.get("authorization_url"))
                .reference(reference)
                .orderNumber(order.getOrderNumber())
                .build();
    }
    // Step 2: Paystack calls this after customer pays
    public void handleWebhook(
            String payload,
            String paystackSignature,
            PaystackWebhookRequest webhookRequest
    ) {
        // Always verify first — reject if signature doesn't match
        if (!paystackService.verifyWebhookSignature(
                payload, paystackSignature)) {
            throw new RuntimeException("Invalid webhook signature");
        }

        if (!"charge.success".equals(webhookRequest.getEvent())) {
            return; // ignore other events for now
        }

        String reference = webhookRequest.getData().getReference();

        Payment payment = paymentRepository
                .findByReference(reference)
                .orElseThrow(() ->
                        new RuntimeException("Payment not found"));

        if (payment.getStatus() == PaymentStatus.SUCCESS) {
            return; // already processed, ignore duplicate webhook
        }

        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setChannel(webhookRequest.getData().getChannel());
        payment.setPaidAt(LocalDateTime.now());
        paymentRepository.save(payment);

        Order order = payment.getOrder();
        order.setPaymentStatus("PAID");
        orderRepository.save(order);

        // Credit the business wallet
        walletService.addToProcessingBalance(order.getId());
// Send customer confirmation email
        try {
            String customerHtml = emailTemplateService.customerOrderConfirmationBody(
                    order.getCustomerName(),
                    order.getOrderNumber(),
                    "₦" + order.getTotalAmount().toPlainString()
            );
            emailService.sendEmail(
                    order.getEmail(),
                    "Order Confirmed - " + order.getOrderNumber(),
                    customerHtml
            );
        } catch (Exception e) {
            // log but don't fail the webhook
            System.err.println("Failed to send customer email: " + e.getMessage());
        }

// Send vendor new order email
        try {
            String sellerHtml = emailTemplateService.sellerNewOrderBody(
                    order.getCustomerName(),
                    order.getOrderNumber(),
                    "₦" + order.getTotalAmount().toPlainString()
            );
            emailService.sendEmail(
                    order.getBusiness().getOwner().getEmail(),
                    "New Order Received - " + order.getOrderNumber(),
                    sellerHtml
            );
        } catch (Exception e) {
            System.err.println("Failed to send vendor email: " + e.getMessage());
        }
    }

    public OrderResponse getOrderByReference(String reference) {
        Payment payment = paymentRepository.findByReference(reference)
                .orElseThrow(() -> new RuntimeException("Payment not found"));
        return checkoutService.getOrderByOrderNumber(payment.getOrder().getOrderNumber());
    }



}