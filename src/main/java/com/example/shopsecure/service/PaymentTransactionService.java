package com.example.shopsecure.service;

import com.example.shopsecure.dto.request.InitializePaymentRequest;
import com.example.shopsecure.dto.request.PaystackWebhookRequest;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.ResourceNotFoundException;
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
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentTransactionService {

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;
    private final WalletService walletService;

    @Transactional
    public PaymentInitData initializePaymentDb(
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

    @Transactional
    public WebhookResult processWebhookPayment(PaystackWebhookRequest webhookRequest) {
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

        walletService.credit(order.getBusiness().getId(), order.getTotalAmount());
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

    public record PaymentInitData(String reference, String orderNumber, String email, BigDecimal amount) {}

    public record WebhookResult(String customerName, String orderNumber, String email,
                                 BigDecimal totalAmount, String sellerEmail) {}
}
