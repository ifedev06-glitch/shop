package com.example.shopsecure.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

import com.example.shopsecure.dto.request.InitializePaymentRequest;
import com.example.shopsecure.dto.request.PaystackWebhookRequest;
import com.example.shopsecure.dto.response.InitializePaymentResponse;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.PaymentException;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock PaymentRepository paymentRepository;
    @Mock OrderRepository orderRepository;
    @Mock OrderItemRepository orderItemRepository;
    @Mock ProductRepository productRepository;
    @Mock PaystackService paystackService;
    @Mock WalletService walletService;
    @Mock EmailService emailService;
    @Mock EmailTemplateService emailTemplateService;
    @Mock CheckoutService checkoutService;

    @Captor ArgumentCaptor<Order> orderCaptor;

    PaymentService paymentService;

    User seller;
    Business business;
    Order order;
    Payment payment;
    Product product;
    OrderItem orderItem;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(
                paymentRepository, orderRepository, orderItemRepository,
                productRepository, paystackService, walletService,
                emailService, emailTemplateService, checkoutService);

        seller = User.builder().id(1L).email("seller@example.com").build();

        business = Business.builder()
                .id(1L).businessName("Test Store").owner(seller).build();

        product = Product.builder()
                .id(1L).name("Widget").price(new BigDecimal("1000"))
                .stock(5).business(business).build();

        order = Order.builder()
                .id(1L).orderNumber("ORD-TEST1").business(business)
                .customerName("John Doe").email("john@example.com")
                .totalAmount(new BigDecimal("6000"))
                .status(OrderStatus.PENDING).paymentStatus("PENDING")
                .stockDeducted(true).shippingFee(new BigDecimal("4000"))
                .createdAt(LocalDateTime.now()).build();

        payment = Payment.builder()
                .id(1L).order(order).reference("PAY-REF123")
                .amount(new BigDecimal("6000"))
                .status(PaymentStatus.PENDING)
                .createdAt(LocalDateTime.now()).build();

        orderItem = OrderItem.builder()
                .id(1L).order(order).product(product).quantity(2)
                .price(new BigDecimal("1000"))
                .subtotal(new BigDecimal("2000")).build();
    }

    // =====================
    // INITIALIZE PAYMENT
    // =====================

    @Test
    void initializePayment_createsNewPaymentAndCallsPaystack() {
        InitializePaymentRequest request = new InitializePaymentRequest();
        request.setOrderNumber("ORD-TEST1");

        when(orderRepository.findByOrderNumber("ORD-TEST1"))
                .thenReturn(Optional.of(order));
        when(paymentRepository.findByOrder_IdAndStatus(1L, PaymentStatus.PENDING))
                .thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(invocation -> {
                    Payment p = invocation.getArgument(0);
                    p.setId(2L);
                    return p;
                });
        when(paystackService.initializeTransaction(
                eq("john@example.com"), eq(new BigDecimal("6000")), anyString()))
                .thenReturn(Map.of("authorization_url", "https://paystack.com/pay/test"));

        InitializePaymentResponse response = paymentService.initializePayment(request);

        assertNotNull(response);
        assertTrue(response.getReference().startsWith("PAY-"));
        assertEquals("https://paystack.com/pay/test", response.getAuthorizationUrl());
        assertEquals("ORD-TEST1", response.getOrderNumber());

        verify(paymentRepository).save(any(Payment.class));
        verify(paystackService).initializeTransaction(
                eq("john@example.com"), eq(new BigDecimal("6000")), anyString());
    }

    @Test
    void initializePayment_reusesExistingPendingPayment() {
        InitializePaymentRequest request = new InitializePaymentRequest();
        request.setOrderNumber("ORD-TEST1");

        when(orderRepository.findByOrderNumber("ORD-TEST1"))
                .thenReturn(Optional.of(order));
        when(paymentRepository.findByOrder_IdAndStatus(1L, PaymentStatus.PENDING))
                .thenReturn(Optional.of(payment));
        when(paystackService.initializeTransaction(
                "john@example.com", new BigDecimal("6000"), "PAY-REF123"))
                .thenReturn(Map.of("authorization_url", "https://paystack.com/rewpay/test"));

        InitializePaymentResponse response = paymentService.initializePayment(request);

        assertEquals("PAY-REF123", response.getReference());
        verify(paymentRepository, never()).save(any(Payment.class));
    }

    @Test
    void initializePayment_orderAlreadyPaid_throws() {
        order.setPaymentStatus("PAID");
        InitializePaymentRequest request = new InitializePaymentRequest();
        request.setOrderNumber("ORD-TEST1");

        when(orderRepository.findByOrderNumber("ORD-TEST1"))
                .thenReturn(Optional.of(order));

        assertThrows(BusinessException.class,
                () -> paymentService.initializePayment(request));
        verify(paystackService, never()).initializeTransaction(any(), any(), any());
    }

    @Test
    void initializePayment_orderNotFound_throws() {
        InitializePaymentRequest request = new InitializePaymentRequest();
        request.setOrderNumber("ORD-MISSING");

        when(orderRepository.findByOrderNumber("ORD-MISSING"))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> paymentService.initializePayment(request));
    }

    // =====================
    // WEBHOOK
    // =====================

    @Test
    void handleWebhook_success_updatesPaymentAndOrderAndCreditsWallet() {
        PaystackWebhookRequest webhookRequest = new PaystackWebhookRequest();
        webhookRequest.setEvent("charge.success");
        PaystackWebhookRequest.PaystackWebhookData data =
                new PaystackWebhookRequest.PaystackWebhookData();
        data.setReference("PAY-REF123");
        data.setChannel("card");
        webhookRequest.setData(data);

        when(paystackService.verifyWebhookSignature("payload", "sig"))
                .thenReturn(true);
        when(paymentRepository.findByReference("PAY-REF123"))
                .thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(orderRepository.save(any(Order.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(walletService.addToProcessingBalance(order.getId()))
                .thenReturn(null);
        when(emailTemplateService.customerOrderConfirmationBody(
                anyString(), anyString(), anyString()))
                .thenReturn("<html>customer</html>");
        when(emailTemplateService.sellerNewOrderBody(
                anyString(), anyString(), anyString()))
                .thenReturn("<html>seller</html>");
        doNothing().when(emailService).sendEmail(anyString(), anyString(), anyString());

        paymentService.handleWebhook("payload", "sig", webhookRequest);

        assertEquals(PaymentStatus.SUCCESS, payment.getStatus());
        assertEquals("card", payment.getChannel());
        assertNotNull(payment.getPaidAt());

        verify(orderRepository).save(orderCaptor.capture());
        assertEquals("PAID", orderCaptor.getValue().getPaymentStatus());

        verify(walletService).addToProcessingBalance(order.getId());
        verify(emailService, times(2)).sendEmail(anyString(), anyString(), anyString());
    }

    @Test
    void handleWebhook_alreadyProcessed_skipsDuplicateEffects() {
        payment.setStatus(PaymentStatus.SUCCESS);

        PaystackWebhookRequest webhookRequest = new PaystackWebhookRequest();
        webhookRequest.setEvent("charge.success");
        PaystackWebhookRequest.PaystackWebhookData data =
                new PaystackWebhookRequest.PaystackWebhookData();
        data.setReference("PAY-REF123");
        webhookRequest.setData(data);

        when(paystackService.verifyWebhookSignature("payload", "sig"))
                .thenReturn(true);
        when(paymentRepository.findByReference("PAY-REF123"))
                .thenReturn(Optional.of(payment));

        paymentService.handleWebhook("payload", "sig", webhookRequest);

        verify(paymentRepository, never()).save(any());
        verify(walletService, never()).addToProcessingBalance(any());
        verify(emailService, never()).sendEmail(anyString(), anyString(), anyString());
    }

    @Test
    void handleWebhook_nonChargeEvent_ignored() {
        PaystackWebhookRequest webhookRequest = new PaystackWebhookRequest();
        webhookRequest.setEvent("transfer.success");
        webhookRequest.setData(new PaystackWebhookRequest.PaystackWebhookData());

        when(paystackService.verifyWebhookSignature("payload", "sig"))
                .thenReturn(true);

        paymentService.handleWebhook("payload", "sig", webhookRequest);

        verify(paymentRepository, never()).findByReference(any());
        verify(walletService, never()).addToProcessingBalance(any());
    }

    @Test
    void handleWebhook_invalidSignature_throws() {
        when(paystackService.verifyWebhookSignature("payload", "sig"))
                .thenReturn(false);

        assertThrows(PaymentException.class,
                () -> paymentService.handleWebhook("payload", "sig", null));
        verify(paymentRepository, never()).findByReference(any());
    }

    @Test
    void handleWebhook_stockDeductionForNonDeductedOrder() {
        order.setStockDeducted(false);

        PaystackWebhookRequest webhookRequest = new PaystackWebhookRequest();
        webhookRequest.setEvent("charge.success");
        PaystackWebhookRequest.PaystackWebhookData data =
                new PaystackWebhookRequest.PaystackWebhookData();
        data.setReference("PAY-REF123");
        data.setChannel("card");
        webhookRequest.setData(data);

        when(paystackService.verifyWebhookSignature("payload", "sig"))
                .thenReturn(true);
        when(paymentRepository.findByReference("PAY-REF123"))
                .thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(orderRepository.save(any(Order.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(walletService.addToProcessingBalance(order.getId()))
                .thenReturn(null);
        when(orderItemRepository.findByOrder_Id(order.getId()))
                .thenReturn(List.of(orderItem));
        when(productRepository.findByIdForUpdate(product.getId()))
                .thenReturn(Optional.of(product));
        when(emailTemplateService.customerOrderConfirmationBody(
                anyString(), anyString(), anyString()))
                .thenReturn("<html>customer</html>");
        when(emailTemplateService.sellerNewOrderBody(
                anyString(), anyString(), anyString()))
                .thenReturn("<html>seller</html>");
        doNothing().when(emailService).sendEmail(anyString(), anyString(), anyString());

        paymentService.handleWebhook("payload", "sig", webhookRequest);

        verify(productRepository).findByIdForUpdate(product.getId());
        assertEquals(3, product.getStock()); // 5 - 2
        assertTrue(order.getStockDeducted());
    }

    @Test
    void handleWebhook_stockClampedAtZero() {
        order.setStockDeducted(false);
        product.setStock(1);

        PaystackWebhookRequest webhookRequest = new PaystackWebhookRequest();
        webhookRequest.setEvent("charge.success");
        PaystackWebhookRequest.PaystackWebhookData data =
                new PaystackWebhookRequest.PaystackWebhookData();
        data.setReference("PAY-REF123");
        data.setChannel("card");
        webhookRequest.setData(data);

        when(paystackService.verifyWebhookSignature("payload", "sig"))
                .thenReturn(true);
        when(paymentRepository.findByReference("PAY-REF123"))
                .thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(orderRepository.save(any(Order.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(walletService.addToProcessingBalance(order.getId()))
                .thenReturn(null);
        when(orderItemRepository.findByOrder_Id(order.getId()))
                .thenReturn(List.of(orderItem));
        when(productRepository.findByIdForUpdate(product.getId()))
                .thenReturn(Optional.of(product));
        when(emailTemplateService.customerOrderConfirmationBody(
                anyString(), anyString(), anyString()))
                .thenReturn("<html>customer</html>");
        when(emailTemplateService.sellerNewOrderBody(
                anyString(), anyString(), anyString()))
                .thenReturn("<html>seller</html>");
        doNothing().when(emailService).sendEmail(anyString(), anyString(), anyString());

        paymentService.handleWebhook("payload", "sig", webhookRequest);

        // stock was 1, requested 2, clamped to 0
        assertEquals(0, product.getStock());
    }

    @Test
    void getOrderByReference_returnsOrder() {
        when(paymentRepository.findByReference("PAY-REF123"))
                .thenReturn(Optional.of(payment));
        com.example.shopsecure.dto.response.OrderResponse mockResponse =
                com.example.shopsecure.dto.response.OrderResponse.builder()
                        .orderNumber("ORD-TEST1").build();
        when(checkoutService.getOrderByOrderNumber("ORD-TEST1"))
                .thenReturn(mockResponse);

        var response = paymentService.getOrderByReference("PAY-REF123");

        assertEquals("ORD-TEST1", response.getOrderNumber());
    }

    @Test
    void getOrderByReference_notFound_throws() {
        when(paymentRepository.findByReference("PAY-MISSING"))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> paymentService.getOrderByReference("PAY-MISSING"));
    }
}
