package com.example.shopsecure.service;

import com.example.shopsecure.dto.request.AddToCartRequest;
import com.example.shopsecure.dto.response.CartItemResponse;
import com.example.shopsecure.dto.response.CartResponse;
import com.example.shopsecure.dto.response.OrderItemResponse;
import com.example.shopsecure.dto.response.OrderResponse;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Transactional
public class CartService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final UserRepository userRepository;
    private final BusinessRepository businessRepository;

    private static final BigDecimal DELIVERY_FEE = BigDecimal.valueOf(4000);


    public CartResponse addToCart(AddToCartRequest request) {

        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found")); // ← changed

        if (product.getStock() < request.getQuantity()) {
            throw new BusinessException("Insufficient stock"); // ← changed
        }

        Business business = product.getBusiness();

        Cart cart = cartRepository
                .findBySessionIdAndBusiness_Id(
                        request.getSessionId(),
                        business.getId()
                )
                .orElseGet(() -> {
                    Cart newCart = Cart.builder()
                            .sessionId(request.getSessionId())
                            .business(business)
                            .totalAmount(BigDecimal.ZERO)
                            .createdAt(LocalDateTime.now())
                            .build();

                    return cartRepository.save(newCart);
                });

        CartItem cartItem = cartItemRepository
                .findByCart_IdAndProduct_Id(cart.getId(), product.getId())
                .orElse(null);

        if (cartItem == null) {

            BigDecimal subtotal =
                    product.getPrice()
                            .multiply(BigDecimal.valueOf(request.getQuantity()));

            cartItem = CartItem.builder()
                    .cart(cart)
                    .product(product)
                    .quantity(request.getQuantity())
                    .price(product.getPrice())
                    .subtotal(subtotal)
                    .build();

        } else {

            int newQuantity =
                    cartItem.getQuantity() + request.getQuantity();

            if (product.getStock() < newQuantity) {
                throw new BusinessException("Insufficient stock"); // ← changed
            }

            cartItem.setQuantity(newQuantity);

            BigDecimal subtotal =
                    product.getPrice()
                            .multiply(BigDecimal.valueOf(newQuantity));

            cartItem.setSubtotal(subtotal);
        }

        cartItemRepository.save(cartItem);

        recalculateCart(cart);

        return mapToResponse(cart);
    }

    public CartResponse getCart(String sessionId, Long businessId) {

        Cart cart = cartRepository
                .findBySessionIdAndBusiness_Id(sessionId, businessId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart not found")); // ← changed

        return mapToResponse(cart);
    }

    public CartResponse updateCartItem(
            String sessionId,
            Long businessId,
            Long productId,
            Integer quantity
    ) {

        Cart cart = cartRepository
                .findBySessionIdAndBusiness_Id(sessionId, businessId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart not found")); // ← changed

        CartItem cartItem = cartItemRepository
                .findByCart_IdAndProduct_Id(cart.getId(), productId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart item not found")); // ← changed

        Product product = cartItem.getProduct();

        if (product.getStock() < quantity) {
            throw new BusinessException("Insufficient stock"); // ← changed
        }

        cartItem.setQuantity(quantity);

        BigDecimal subtotal =
                product.getPrice()
                        .multiply(BigDecimal.valueOf(quantity));

        cartItem.setSubtotal(subtotal);

        cartItemRepository.save(cartItem);

        recalculateCart(cart);

        return mapToResponse(cart);
    }

    public void removeCartItem(
            String sessionId,
            Long businessId,
            Long productId
    ) {

        Cart cart = cartRepository
                .findBySessionIdAndBusiness_Id(sessionId, businessId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart not found")); // ← changed

        CartItem cartItem = cartItemRepository
                .findByCart_IdAndProduct_Id(cart.getId(), productId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart item not found")); // ← changed

        cartItemRepository.delete(cartItem);

        recalculateCart(cart);
    }

    public void clearCart(Cart cart) {

        List<CartItem> items =
                cartItemRepository.findByCart_Id(cart.getId());

        cartItemRepository.deleteAll(items);

        cart.setTotalAmount(BigDecimal.ZERO);

        cartRepository.save(cart);
    }

    private void recalculateCart(Cart cart) {

        List<CartItem> items =
                cartItemRepository.findByCart_Id(cart.getId());

        BigDecimal total = items.stream()
                .map(CartItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        cart.setTotalAmount(total);

        cartRepository.save(cart);
    }

    private User getCurrentUser() {
        String email = SecurityContextHolder.getContext()
                .getAuthentication()
                .getName();

        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found")); // ← changed
    }


    private OrderResponse mapToOrderResponse(Order order) {

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

    private CartResponse mapToResponse(Cart cart) {

        List<CartItem> items =
                cartItemRepository.findByCart_Id(cart.getId());

        List<CartItemResponse> itemResponses =
                items.stream()
                        .map(item -> CartItemResponse.builder()
                                .productId(item.getProduct().getId())
                                .productName(item.getProduct().getName())
                                .imageUrl(
                                        item.getProduct()
                                                .getImageUrls()
                                                .isEmpty()
                                                ? null
                                                : item.getProduct()
                                                .getImageUrls()
                                                .get(0)
                                )
                                .price(item.getPrice())
                                .quantity(item.getQuantity())
                                .subtotal(item.getSubtotal())
                                .build())
                        .toList();

        return CartResponse.builder()
                .cartId(cart.getId())
                .sessionId(cart.getSessionId())
                .businessName(cart.getBusiness().getBusinessName())
                .subtotal(cart.getTotalAmount())
                .deliveryFee(DELIVERY_FEE)
                .totalAmount(cart.getTotalAmount().add(DELIVERY_FEE))
                .items(itemResponses)
                .build();
    }

    //////////////////////////////////Temoorary, till i clean code//////
    ///
    @Transactional(readOnly = true)
    public List<OrderResponse> getPendingOrders() {
        User currentUser = getCurrentUser();

        Business business = businessRepository
                .findByOwner_Id(currentUser.getId())
                .orElseThrow(() ->
                        new ResourceNotFoundException("Business not found")); // ← changed

        List<Order> pendingOrders = orderRepository
                .findByBusiness_IdAndStatusAndPaymentStatus(
                        business.getId(),
                        OrderStatus.PENDING,
                        "PAID"
                );

        List<Order> readyForPickupOrders = orderRepository
                .findByBusiness_IdAndStatus(
                        business.getId(),
                        OrderStatus.READY_FOR_PICKUP
                );

        return Stream.concat(pendingOrders.stream(), readyForPickupOrders.stream())
                .map(this::mapToOrderResponse)
                .toList();
    }
}