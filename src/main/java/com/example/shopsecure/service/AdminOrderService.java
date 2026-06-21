package com.example.shopsecure.service;

import com.example.shopsecure.dto.request.UpdateOrderStatusRequest;
import com.example.shopsecure.dto.response.OrderItemResponse;
import com.example.shopsecure.dto.response.OrderResponse;
import com.example.shopsecure.entity.Order;
import com.example.shopsecure.entity.OrderStatus;
import com.example.shopsecure.repository.OrderItemRepository;
import com.example.shopsecure.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@Service
@RequiredArgsConstructor
@Transactional
public class AdminOrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final WalletService walletService;
    private final EmailService emailService;
    private final EmailTemplateService emailTemplateService;

    @Transactional(readOnly = true)
    public Page<OrderResponse> getAllOrders(int page) {
        Pageable pageable = PageRequest.of(page, 20, Sort.by("createdAt").descending());
        return orderRepository.findAll(pageable)
                .map(this::mapToResponse);}

    @Transactional(readOnly = true)
    public Page<OrderResponse> getPendingOrders(int page) {
        Pageable pageable = PageRequest.of(page, 20, Sort.by("createdAt").descending());
        return orderRepository
                .findByStatusAndPaymentStatus(
                        OrderStatus.PENDING,
                        "PAID",
                        pageable
                )
                .map(this::mapToResponse);
    }
    @Transactional(readOnly = true)
    public OrderResponse getOrderByOrderNumber(
            String orderNumber
    ) {

        Order order = orderRepository
                .findByOrderNumber(orderNumber)
                .orElseThrow(() ->
                        new RuntimeException("Order not found"));

        return mapToResponse(order);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getOrdersByBusiness(
            Long businessId
    ) {

        return orderRepository
                .findByBusiness_Id(businessId)
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> searchByBusinessName(
            String businessName
    ) {

        return orderRepository
                .findByBusiness_BusinessNameContainingIgnoreCase(
                        businessName
                )
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    public OrderResponse updateOrderStatus(
            Long orderId,
            UpdateOrderStatusRequest request
    ) {

        Order order = orderRepository
                .findById(orderId)
                .orElseThrow(() ->
                        new RuntimeException("Order not found"));

        order.setStatus(request.getStatus());

        Order saved = orderRepository.save(order);

        if (request.getStatus() == OrderStatus.SHIPPED
                && !Boolean.TRUE.equals(saved.getSettled())) {
            walletService.releaseFunds(saved.getId());
        }

        // Shipped emails
        if (request.getStatus() == OrderStatus.SHIPPED) {
            try {
                emailService.sendEmail(
                        saved.getEmail(),
                        "Your Order is On Its Way - " + saved.getOrderNumber(),
                        emailTemplateService.orderShippedBody(
                                saved.getCustomerName(),
                                saved.getOrderNumber(),
                                saved.getPhoneNumber()
                        )
                );
            } catch (Exception e) {
                System.err.println("Failed to send shipped email to customer: " + e.getMessage());
            }

            try {
                emailService.sendEmail(
                        saved.getBusiness().getOwner().getEmail(),
                        "Order Shipped - " + saved.getOrderNumber(),
                        emailTemplateService.orderShippedBody(
                                saved.getCustomerName(),
                                saved.getOrderNumber(),
                                saved.getPhoneNumber()
                        )
                );
            } catch (Exception e) {
                System.err.println("Failed to send shipped email to vendor: " + e.getMessage());
            }
        }

        // Delivered emails
        if (request.getStatus() == OrderStatus.DELIVERED) {
            try {
                emailService.sendEmail(
                        saved.getEmail(),
                        "Order Delivered - " + saved.getOrderNumber(),
                        emailTemplateService.orderDeliveredBody(
                                saved.getCustomerName(),
                                saved.getOrderNumber(),
                                "₦" + saved.getTotalAmount().toPlainString()));

            } catch (Exception e) {
                System.err.println("Failed to send delivered email to customer: " + e.getMessage());
            }

            try {
                emailService.sendEmail(
                        saved.getBusiness().getOwner().getEmail(),
                        "Order Delivered - " + saved.getOrderNumber(),
                        emailTemplateService.orderDeliveredBody(
                                saved.getCustomerName(),
                                saved.getOrderNumber(),
                                "₦" + saved.getTotalAmount().toPlainString()
                        )
                );
            } catch (Exception e) {
                System.err.println("Failed to send delivered email to vendor: " + e.getMessage());
            }
        }

        return mapToResponse(saved);
    }

    private OrderResponse mapToResponse(Order order) {

        List<OrderItemResponse> items =
                orderItemRepository.findByOrder_Id(order.getId())
                        .stream()
                        .map(item -> OrderItemResponse.builder()
                                .productName(item.getProduct().getName())
                                .quantity(item.getQuantity())
                                .price(item.getPrice())
                                .subtotal(item.getSubtotal())
                                .build())
                        .toList();

        return OrderResponse.builder()
                .orderId(order.getId())
                .orderNumber(order.getOrderNumber())
                .customerName(order.getCustomerName())
                .email(order.getEmail())
                .phoneNumber(order.getPhoneNumber())
                .deliveryAddress(order.getDeliveryAddress())
                .title(order.getTitle())
                .description(order.getDescription())
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus())
                .items(items)
                .createdAt(order.getCreatedAt())
                .build();
    }
}
