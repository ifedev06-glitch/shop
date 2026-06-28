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
import com.example.shopsecure.entity.OrderItem;
import com.example.shopsecure.entity.Product;
import com.example.shopsecure.repository.OrderItemRepository;
import com.example.shopsecure.repository.OrderRepository;
import com.example.shopsecure.repository.PaymentRepository;
import com.example.shopsecure.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;
    private final PaystackService paystackService;
    private final WalletService walletService;
    private final EmailService emailService;
    private final EmailTemplateService emailTemplateService;
    private final CheckoutService checkoutService;

    public InitializePaymentResponse initializePayment(
            InitializePaymentRequest request
    ) {
        PaymentInitData data = initializePaymentDb(request);

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

    @Transactional
    protected PaymentInitData initializePaymentDb(
            InitializePaymentRequest request
    ) {
        Order order = orderRepository
                .findByOrderNumber(request.getOrderNumber())
                .orElseThrow(() ->
                        new ResourceNotFoundException("Order not found"));

        if (order.getPaymentStatus().equals("PAID")) {
            throw new BusinessException("Order already paid");
        }

        Optional<Payment> existingPayment = paymentRepository
                .findByOrder_IdAndStatus(
                        order.getId(), PaymentStatus.PENDING);

        if (existingPayment.isPresent()) {
            Payment payment = existingPayment.get();
            return new PaymentInitData(
                    payment.getReference(),
                    order.getOrderNumber(),
                    order.getEmail(),
                    order.getTotalAmount()
            );
        }

        String reference = "PAY-" +
                UUID.randomUUID().toString().toUpperCase();

        Payment payment = Payment.builder()
                .order(order)
                .reference(reference)
                .amount(order.getTotalAmount())
                .status(PaymentStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();

        paymentRepository.save(payment);

        log.info("Payment initialized: orderNumber={}, reference={}", order.getOrderNumber(), reference);

        return new PaymentInitData(
                reference,
                order.getOrderNumber(),
                order.getEmail(),
                order.getTotalAmount()
        );
    }

    private record PaymentInitData(String reference, String orderNumber, String email, BigDecimal amount) {}

    public void handleWebhook(
            String payload,
            String paystackSignature,
            PaystackWebhookRequest webhookRequest
    ) {
        if (!paystackService.verifyWebhookSignature(
                payload, paystackSignature)) {
            throw new PaymentException("Invalid webhook signature");
        }

        if (!"charge.success".equals(webhookRequest.getEvent())) {
            return;
        }

        // DB work in transaction (payment, order, wallet, stock deduction)
        WebhookResult result = processWebhookPayment(webhookRequest);

        if (result == null) {
            return;
        }

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
        } catch (Exception e) {
            log.error("Failed to send customer email: {}", e.getMessage());
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
        } catch (Exception e) {
            log.error("Failed to send vendor email: {}", e.getMessage());
        }
    }

    @Transactional
    protected WebhookResult processWebhookPayment(PaystackWebhookRequest webhookRequest) {
        String reference = webhookRequest.getData().getReference();
        log.info("Charge.success webhook received: reference={}", reference);

        Payment payment = paymentRepository
                .findByReference(reference)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Payment not found"));

        if (payment.getStatus() == PaymentStatus.SUCCESS) {
            log.info("Payment already SUCCESS: reference={}", reference);
            return null;
        }

        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setChannel(webhookRequest.getData().getChannel());
        payment.setPaidAt(LocalDateTime.now());
        paymentRepository.save(payment);

        Order order = payment.getOrder();
        order.setPaymentStatus("PAID");
        orderRepository.save(order);

        walletService.addToProcessingBalance(order.getId());
        log.info("Wallet credited for orderId={}", order.getId());

        // deduct stock if not already deducted (checkout link orders)
        if (!Boolean.TRUE.equals(order.getStockDeducted())) {
            log.info("Stock deduction started for order: orderNumber={}", order.getOrderNumber());
            List<OrderItem> orderItems = orderItemRepository.findByOrder_Id(order.getId());
            for (OrderItem item : orderItems) {
                Product product = productRepository.findByIdForUpdate(item.getProduct().getId())
                        .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
                int newStock = product.getStock() - item.getQuantity();
                if (newStock < 0) {
                    log.warn("Insufficient stock during deduction: productId={}, currentStock={}, requested={}",
                            product.getId(), product.getStock(), item.getQuantity());
                    newStock = 0;
                }
                product.setStock(newStock);
                productRepository.save(product);
            }
            order.setStockDeducted(true);
            orderRepository.save(order);
            log.info("Stock deducted after successful payment: orderNumber={}", order.getOrderNumber());
        }

        return new WebhookResult(
                order.getCustomerName(),
                order.getOrderNumber(),
                order.getEmail(),
                order.getTotalAmount(),
                order.getBusiness().getOwner().getEmail()
        );
    }

    private record WebhookResult(String customerName, String orderNumber, String email,
                                 BigDecimal totalAmount, String sellerEmail) {}

    @Transactional(readOnly = true)
    public OrderResponse getOrderByReference(String reference) {
        Payment payment = paymentRepository.findByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        return checkoutService.getOrderByOrderNumber(payment.getOrder().getOrderNumber());
    }
}