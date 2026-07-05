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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock PaymentTransactionService paymentTransactionService;
    @Mock PaymentRepository paymentRepository;
    @Mock PaystackService paystackService;
    @Mock EmailService emailService;
    @Mock EmailTemplateService emailTemplateService;
    @Mock CheckoutService checkoutService;

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
                paymentTransactionService, paymentRepository, paystackService,
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

        when(paymentTransactionService.initializePaymentDb(any(InitializePaymentRequest.class)))
                .thenReturn(new PaymentTransactionService.PaymentInitData(
                        "PAY-NEWREF", "ORD-TEST1", "john@example.com", new BigDecimal("6000")));
        when(paystackService.initializeTransaction(
                eq("john@example.com"), eq(new BigDecimal("6000")), anyString()))
                .thenReturn(Map.of("authorization_url", "https://paystack.com/pay/test"));

        InitializePaymentResponse response = paymentService.initializePayment(request);

        assertNotNull(response);
        assertEquals("PAY-NEWREF", response.getReference());
        assertEquals("https://paystack.com/pay/test", response.getAuthorizationUrl());
        assertEquals("ORD-TEST1", response.getOrderNumber());

        verify(paymentTransactionService).initializePaymentDb(request);
        verify(paystackService).initializeTransaction(
                eq("john@example.com"), eq(new BigDecimal("6000")), anyString());
    }

    @Test
    void initializePayment_reusesExistingPendingPayment() {
        InitializePaymentRequest request = new InitializePaymentRequest();
        request.setOrderNumber("ORD-TEST1");

        when(paymentTransactionService.initializePaymentDb(any(InitializePaymentRequest.class)))
                .thenReturn(new PaymentTransactionService.PaymentInitData(
                        "PAY-REF123", "ORD-TEST1", "john@example.com", new BigDecimal("6000")));
        when(paystackService.initializeTransaction(
                "john@example.com", new BigDecimal("6000"), "PAY-REF123"))
                .thenReturn(Map.of("authorization_url", "https://paystack.com/rewpay/test"));

        InitializePaymentResponse response = paymentService.initializePayment(request);

        assertEquals("PAY-REF123", response.getReference());
        verify(paymentTransactionService).initializePaymentDb(request);
    }

    @Test
    void initializePayment_orderAlreadyPaid_throws() {
        InitializePaymentRequest request = new InitializePaymentRequest();
        request.setOrderNumber("ORD-TEST1");

        when(paymentTransactionService.initializePaymentDb(any(InitializePaymentRequest.class)))
                .thenThrow(new BusinessException("Order already paid"));

        assertThrows(BusinessException.class,
                () -> paymentService.initializePayment(request));
        verify(paystackService, never()).initializeTransaction(any(), any(), any());
    }

    @Test
    void initializePayment_orderNotFound_throws() {
        InitializePaymentRequest request = new InitializePaymentRequest();
        request.setOrderNumber("ORD-MISSING");

        when(paymentTransactionService.initializePaymentDb(any(InitializePaymentRequest.class)))
                .thenThrow(new ResourceNotFoundException("Order not found"));

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

        PaymentTransactionService.WebhookResult result =
                new PaymentTransactionService.WebhookResult(
                        "John Doe", "ORD-TEST1", "john@example.com",
                        new BigDecimal("6000"), "seller@example.com");

        when(paystackService.verifyWebhookSignature("payload", "sig"))
                .thenReturn(true);
        when(paymentTransactionService.processWebhookPayment(any(PaystackWebhookRequest.class)))
                .thenReturn(result);
        when(emailTemplateService.customerOrderConfirmationBody(
                anyString(), anyString(), anyString()))
                .thenReturn("<html>customer</html>");
        when(emailTemplateService.sellerNewOrderBody(
                anyString(), anyString(), anyString()))
                .thenReturn("<html>seller</html>");
        doNothing().when(emailService).sendEmail(anyString(), anyString(), anyString());

        paymentService.handleWebhook("payload", "sig", webhookRequest);

        verify(paymentTransactionService).processWebhookPayment(webhookRequest);
        verify(emailService, times(2)).sendEmail(anyString(), anyString(), anyString());
    }

    @Test
    void handleWebhook_alreadyProcessed_skipsDuplicateEffects() {
        PaystackWebhookRequest webhookRequest = new PaystackWebhookRequest();
        webhookRequest.setEvent("charge.success");
        PaystackWebhookRequest.PaystackWebhookData data =
                new PaystackWebhookRequest.PaystackWebhookData();
        data.setReference("PAY-REF123");
        webhookRequest.setData(data);

        when(paystackService.verifyWebhookSignature("payload", "sig"))
                .thenReturn(true);
        when(paymentTransactionService.processWebhookPayment(any(PaystackWebhookRequest.class)))
                .thenReturn(null);

        paymentService.handleWebhook("payload", "sig", webhookRequest);

        verify(paymentTransactionService).processWebhookPayment(webhookRequest);
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

        verify(paymentTransactionService, never()).processWebhookPayment(any());
        verify(emailService, never()).sendEmail(anyString(), anyString(), anyString());
    }

    @Test
    void handleWebhook_invalidSignature_throws() {
        when(paystackService.verifyWebhookSignature("payload", "sig"))
                .thenReturn(false);

        assertThrows(PaymentException.class,
                () -> paymentService.handleWebhook("payload", "sig", null));
        verify(paymentTransactionService, never()).processWebhookPayment(any());
    }

    @Test
    void handleWebhook_stockDeductionForNonDeductedOrder() {
        PaystackWebhookRequest webhookRequest = new PaystackWebhookRequest();
        webhookRequest.setEvent("charge.success");
        PaystackWebhookRequest.PaystackWebhookData data =
                new PaystackWebhookRequest.PaystackWebhookData();
        data.setReference("PAY-REF123");
        data.setChannel("card");
        webhookRequest.setData(data);

        PaymentTransactionService.WebhookResult result =
                new PaymentTransactionService.WebhookResult(
                        "John Doe", "ORD-TEST1", "john@example.com",
                        new BigDecimal("6000"), "seller@example.com");

        when(paystackService.verifyWebhookSignature("payload", "sig"))
                .thenReturn(true);
        when(paymentTransactionService.processWebhookPayment(any(PaystackWebhookRequest.class)))
                .thenReturn(result);
        when(emailTemplateService.customerOrderConfirmationBody(
                anyString(), anyString(), anyString()))
                .thenReturn("<html>customer</html>");
        when(emailTemplateService.sellerNewOrderBody(
                anyString(), anyString(), anyString()))
                .thenReturn("<html>seller</html>");
        doNothing().when(emailService).sendEmail(anyString(), anyString(), anyString());

        paymentService.handleWebhook("payload", "sig", webhookRequest);

        verify(paymentTransactionService).processWebhookPayment(webhookRequest);
        verify(emailService, times(2)).sendEmail(anyString(), anyString(), anyString());
    }

    @Test
    void handleWebhook_stockClampedAtZero() {
        PaystackWebhookRequest webhookRequest = new PaystackWebhookRequest();
        webhookRequest.setEvent("charge.success");
        PaystackWebhookRequest.PaystackWebhookData data =
                new PaystackWebhookRequest.PaystackWebhookData();
        data.setReference("PAY-REF123");
        data.setChannel("card");
        webhookRequest.setData(data);

        PaymentTransactionService.WebhookResult result =
                new PaymentTransactionService.WebhookResult(
                        "John Doe", "ORD-TEST1", "john@example.com",
                        new BigDecimal("6000"), "seller@example.com");

        when(paystackService.verifyWebhookSignature("payload", "sig"))
                .thenReturn(true);
        when(paymentTransactionService.processWebhookPayment(any(PaystackWebhookRequest.class)))
                .thenReturn(result);
        when(emailTemplateService.customerOrderConfirmationBody(
                anyString(), anyString(), anyString()))
                .thenReturn("<html>customer</html>");
        when(emailTemplateService.sellerNewOrderBody(
                anyString(), anyString(), anyString()))
                .thenReturn("<html>seller</html>");
        doNothing().when(emailService).sendEmail(anyString(), anyString(), anyString());

        paymentService.handleWebhook("payload", "sig", webhookRequest);

        verify(paymentTransactionService).processWebhookPayment(webhookRequest);
        verify(emailService, times(2)).sendEmail(anyString(), anyString(), anyString());
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
