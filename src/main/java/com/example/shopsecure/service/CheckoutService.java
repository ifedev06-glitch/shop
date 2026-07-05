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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j


public class CheckoutService {

        private final CartRepository cartRepository;
        private final CartItemRepository cartItemRepository;
        private final OrderRepository orderRepository;
        private final OrderItemRepository orderItemRepository;
        private final CartService cartService;
        private final ProductRepository productRepository;

        private final ShippingService shippingService;

        private static final BigDecimal ZERO = BigDecimal.ZERO;

        // ---------------------------
        // CHECKOUT SUMMARY (PREVIEW)
        // ---------------------------
        public CheckoutSummaryResponse calculateCheckout(
                CheckoutSummaryRequest request
        ) {
            log.info("calculateCheckout request: sessionId={}, businessId={}, shippingArea={}",
                    request.getSessionId(),
                    request.getBusinessId(),
                    request.getShippingArea());
            Cart cart = cartRepository
                    .findBySessionIdAndBusiness_Id(
                            request.getSessionId(),
                            request.getBusinessId()
                    )
                    .orElseThrow(() ->
                            new ResourceNotFoundException("Cart not found"));

            List<CartItem> cartItems =
                    cartItemRepository.findByCart_Id(cart.getId());

            if (cartItems.isEmpty()) {
                throw new BusinessException("Cart is empty");
            }

            validateStock(cartItems);

            BigDecimal subtotal = cart.getTotalAmount();

            int totalItems = cartItems.stream()
                    .mapToInt(CartItem::getQuantity)
                    .sum();

            ShippingRate rate =
                    shippingService.getShippingRate(request.getShippingArea());

            BigDecimal deliveryFee = rate.getFee();
            log.info("Shipping fee calculated: area={}, fee={}", request.getShippingArea(), deliveryFee);

            BigDecimal totalAmount =
                    subtotal.add(deliveryFee);

            return CheckoutSummaryResponse.builder()
                    .subtotal(subtotal)
                    .deliveryFee(deliveryFee)
                    .totalAmount(totalAmount)
                    .totalItems(totalItems)
                    .build();
        }

        // ---------------------------
        // FINAL CHECKOUT
        // ---------------------------
        public OrderResponse checkout(CheckoutRequest request) {

            Cart cart = cartRepository
                    .findBySessionIdAndBusiness_Id(
                            request.getSessionId(),
                            request.getBusinessId()
                    )
                    .orElseThrow(() ->
                            new ResourceNotFoundException("Cart not found"));

            List<CartItem> cartItems =
                    cartItemRepository.findByCart_Id(cart.getId());

            if (cartItems.isEmpty()) {
                throw new BusinessException("Cart is empty");
            }

            validateStock(cartItems);

            String orderNumber =
                    "ORD-" + UUID.randomUUID()
                            .toString()
                            .substring(0, 8)
                            .toUpperCase();

            ShippingRate rate =
                    shippingService.getShippingRate(request.getShippingArea());

            BigDecimal shippingFee = rate.getFee();

            BigDecimal totalWithDelivery =
                    cart.getTotalAmount().add(shippingFee);

            Order order = Order.builder()
                    .orderNumber(orderNumber)
                    .business(cart.getBusiness())
                    .customerName(request.getCustomerName())
                    .email(request.getEmail())
                    .phoneNumber(request.getPhoneNumber())
                    .deliveryAddress(request.getDeliveryAddress())

                    .shippingArea(request.getShippingArea())
                    .shippingFee(shippingFee)

                    .totalAmount(totalWithDelivery)
                    .status(OrderStatus.PENDING)
                    .paymentStatus("PENDING")
                    .stockDeducted(false)
                    .createdAt(LocalDateTime.now())
                    .build();

            Order savedOrder = orderRepository.save(order);
            log.info("Order created: orderNumber={}, businessId={}", savedOrder.getOrderNumber(), savedOrder.getBusiness().getId());

            for (CartItem cartItem : cartItems) {

                OrderItem orderItem = OrderItem.builder()
                        .order(savedOrder)
                        .product(cartItem.getProduct())
                        .quantity(cartItem.getQuantity())
                        .price(cartItem.getPrice())
                        .subtotal(cartItem.getSubtotal())
                        .build();

                orderItemRepository.save(orderItem);

                Product product = productRepository.findByIdForUpdate(cartItem.getProduct().getId())
                        .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
                int newStock = product.getStock() - cartItem.getQuantity();
                product.setStock(Math.max(newStock, 0));
                productRepository.save(product);
            }

            savedOrder.setStockDeducted(true);

            cartService.clearCart(cart);

            return mapToResponse(savedOrder);
        }

        // ---------------------------
        // STOCK VALIDATION
        // ---------------------------
        private void validateStock(List<CartItem> cartItems) {

            for (CartItem cartItem : cartItems) {

                Product product = cartItem.getProduct();

                if (product.getStock() < cartItem.getQuantity()) {
                    throw new BusinessException(
                            product.getName() + " is out of stock"
                    );
                }
            }
        }

        // ---------------------------
        // GET ORDER
        // ---------------------------
        public OrderResponse getOrderByOrderNumber(String orderNumber) {

            Order order = orderRepository
                    .findByOrderNumber(orderNumber)
                    .orElseThrow(() ->
                            new ResourceNotFoundException("Order not found"));

            return mapToResponse(order);
        }

    // ---------------------------
    // UPDATE STATUS
    // ---------------------------
    public OrderResponse orderReadyForDelivery(
            Long orderId,
            UpdateOrderStatusRequest request
    ) {

        Order order = orderRepository
                .findById(orderId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Order not found"));

        OrderStatus previousStatus = order.getStatus();
        order.setStatus(request.getStatus());

        OrderResponse response = mapToResponse(orderRepository.save(order));
        log.info("Order status changed: orderNumber={}, from={}, to={}",
                response.getOrderNumber(), previousStatus, request.getStatus());

        return response;
    }

        // ---------------------------
        // MAPPER
        // ---------------------------
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
                    .shippingArea(order.getShippingArea())
                    .shippingFee(order.getShippingFee())
                    .totalAmount(order.getTotalAmount())
                    .status(order.getStatus())
                    .items(items)
                    .createdAt(order.getCreatedAt())
                    .build();
        }
    }