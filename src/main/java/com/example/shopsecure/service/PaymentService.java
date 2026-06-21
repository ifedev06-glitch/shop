package com.example.shopsecure.service;

import com.example.shopsecure.dto.request.InitializePaymentRequest;
import com.example.shopsecure.dto.request.PaystackWebhookRequest;
import com.example.shopsecure.dto.response.InitializePaymentResponse;
import com.example.shopsecure.dto.response.OrderResponse;
import com.example.shopsecure.entity.Order;
import com.example.shopsecure.entity.Payment;
import com.example.shopsecure.entity.PaymentStatus;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.PaymentException;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.repository.OrderRepository;
import com.example.shopsecure.repository.PaymentRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
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
                        new ResourceNotFoundException("Order not found")); // ← changed

        if (order.getPaymentStatus().equals("PAID")) {
            throw new BusinessException("Order already paid"); // ← changed
        }

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
                    .build();
        }

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

    public void handleWebhook(
            String payload,
            String paystackSignature,
            PaystackWebhookRequest webhookRequest
    ) {
        if (!paystackService.verifyWebhookSignature(
                payload, paystackSignature)) {
            throw new PaymentException("Invalid webhook signature"); // ← changed
        }

        if (!"charge.success".equals(webhookRequest.getEvent())) {
            return;
        }

        String reference = webhookRequest.getData().getReference();

        Payment payment = paymentRepository
                .findByReference(reference)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Payment not found")); // ← changed

        if (payment.getStatus() == PaymentStatus.SUCCESS) {
            return;
        }

        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setChannel(webhookRequest.getData().getChannel());
        payment.setPaidAt(LocalDateTime.now());
        paymentRepository.save(payment);

        Order order = payment.getOrder();
        order.setPaymentStatus("PAID");
        orderRepository.save(order);

        walletService.addToProcessingBalance(order.getId());

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
            log.error("Failed to send customer email: {}", e.getMessage()); // ← replaced System.err
        }

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
            log.error("Failed to send vendor email: {}", e.getMessage()); // ← replaced System.err
        }
    }

    public OrderResponse getOrderByReference(String reference) {
        Payment payment = paymentRepository.findByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found")); // ← changed
        return checkoutService.getOrderByOrderNumber(payment.getOrder().getOrderNumber());
    }
}