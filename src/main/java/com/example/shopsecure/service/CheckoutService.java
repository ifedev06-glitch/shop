package com.example.shopsecure.service;

import com.example.shopsecure.dto.request.CheckoutRequest;
import com.example.shopsecure.dto.request.CheckoutSummaryRequest;
import com.example.shopsecure.dto.request.UpdateOrderStatusRequest;
import com.example.shopsecure.dto.response.CheckoutSummaryResponse;
import com.example.shopsecure.dto.response.OrderItemResponse;
import com.example.shopsecure.dto.response.OrderResponse;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class CheckoutService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CartService cartService;
    private final ProductRepository productRepository;
    private final WalletService walletService;
    private final EmailTemplateService emailTemplateService;
    private final EmailService emailService;

    private static final BigDecimal DELIVERY_FEE = BigDecimal.valueOf(4000);

    public CheckoutSummaryResponse calculateCheckout(
            CheckoutSummaryRequest request
    ) {

        Cart cart = cartRepository
                .findBySessionIdAndBusiness_Id(
                        request.getSessionId(),
                        request.getBusinessId()
                )
                .orElseThrow(() ->
                        new ResourceNotFoundException("Cart not found")); // ← changed

        List<CartItem> cartItems =
                cartItemRepository.findByCart_Id(cart.getId());

        if (cartItems.isEmpty()) {
            throw new BusinessException("Cart is empty"); // ← changed
        }

        validateStock(cartItems);

        BigDecimal subtotal = cart.getTotalAmount();

        Integer totalItems = cartItems.stream()
                .mapToInt(CartItem::getQuantity)
                .sum();

        BigDecimal deliveryFee = BigDecimal.valueOf(4000);

        BigDecimal totalAmount =
                subtotal.add(deliveryFee);

        return CheckoutSummaryResponse.builder()
                .subtotal(subtotal)
                .deliveryFee(deliveryFee)
                .totalAmount(totalAmount)
                .totalItems(totalItems)
                .build();
    }

    public OrderResponse checkout(CheckoutRequest request) {

        Cart cart = cartRepository
                .findBySessionIdAndBusiness_Id(
                        request.getSessionId(),
                        request.getBusinessId()
                )
                .orElseThrow(() -> new ResourceNotFoundException("Cart not found")); // ← changed

        List<CartItem> cartItems =
                cartItemRepository.findByCart_Id(cart.getId());

        if (cartItems.isEmpty()) {
            throw new BusinessException("Cart is empty"); // ← changed
        }

        validateStock(cartItems);

        String orderNumber =
                "ORD-" + UUID.randomUUID()
                        .toString()
                        .substring(0, 8)
                        .toUpperCase();

        BigDecimal totalWithDelivery = cart.getTotalAmount().add(DELIVERY_FEE);

        Order order = Order.builder()
                .orderNumber(orderNumber)
                .business(cart.getBusiness())
                .customerName(request.getCustomerName())
                .email(request.getEmail())
                .phoneNumber(request.getPhoneNumber())
                .deliveryAddress(request.getDeliveryAddress())
                .totalAmount(totalWithDelivery)
                .status(OrderStatus.PENDING)
                .paymentStatus("PENDING")
                .createdAt(LocalDateTime.now())
                .build();

        Order savedOrder = orderRepository.save(order);

        for (CartItem cartItem : cartItems) {

            OrderItem orderItem = OrderItem.builder()
                    .order(savedOrder)
                    .product(cartItem.getProduct())
                    .quantity(cartItem.getQuantity())
                    .price(cartItem.getPrice())
                    .subtotal(cartItem.getSubtotal())
                    .build();

            orderItemRepository.save(orderItem);
            Product product = cartItem.getProduct();
            product.setStock(product.getStock() - cartItem.getQuantity());
            productRepository.save(product);
        }

        cartService.clearCart(cart);

        return mapToResponse(savedOrder);
    }

    private void validateStock(List<CartItem> cartItems) {

        for (CartItem cartItem : cartItems) {

            Product product = cartItem.getProduct();

            if (product.getStock() < cartItem.getQuantity()) {
                throw new BusinessException(
                        product.getName() + " is out of stock" // ← changed
                );
            }
        }
    }

    public OrderResponse getOrderByOrderNumber(
            String orderNumber
    ) {

        Order order = orderRepository
                .findByOrderNumber(orderNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found")); // ← changed

        return mapToResponse(order);
    }

    public OrderResponse orderReadyForDelivery(
            Long orderId,
            UpdateOrderStatusRequest request
    ) {

        Order order = orderRepository
                .findById(orderId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Order not found")); // ← changed

        order.setStatus(request.getStatus());

        Order saved = orderRepository.save(order);
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
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus())
                .items(items)
                .createdAt(order.getCreatedAt())
                .build();
    }
}