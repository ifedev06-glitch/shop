package com.example.shopsecure.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

import com.example.shopsecure.dto.request.CheckoutRequest;
import com.example.shopsecure.dto.response.OrderResponse;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.exception.BusinessException;
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
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class CheckoutServiceTest {

    @Mock CartRepository cartRepository;
    @Mock CartItemRepository cartItemRepository;
    @Mock OrderRepository orderRepository;
    @Mock OrderItemRepository orderItemRepository;
    @Mock CartService cartService;
    @Mock ProductRepository productRepository;
    @Mock ShippingService shippingService;

    @Captor ArgumentCaptor<Order> orderCaptor;
    @Captor ArgumentCaptor<OrderItem> orderItemCaptor;
    @Captor ArgumentCaptor<Product> productCaptor;

    CheckoutService checkoutService;

    Business business;
    Product product;
    Cart cart;
    CartItem cartItem;
    CheckoutRequest request;
    ShippingRate shippingRate;

    @BeforeEach
    void setUp() {
        checkoutService = new CheckoutService(
                cartRepository, cartItemRepository, orderRepository,
                orderItemRepository, cartService, productRepository,
                shippingService);

        business = Business.builder()
                .id(1L).businessName("Test Store").build();

        product = Product.builder()
                .id(1L).name("Widget").price(new BigDecimal("1000"))
                .stock(10).business(business).build();

        cart = Cart.builder()
                .id(1L).sessionId("session-1").business(business)
                .totalAmount(new BigDecimal("2000")).build();

        cartItem = CartItem.builder()
                .id(1L).cart(cart).product(product).quantity(2)
                .price(new BigDecimal("1000"))
                .subtotal(new BigDecimal("2000")).build();

        request = new CheckoutRequest();
        request.setSessionId("session-1");
        request.setBusinessId(1L);
        request.setCustomerName("John Doe");
        request.setEmail("john@example.com");
        request.setPhoneNumber("08012345678");
        request.setDeliveryAddress("123 Street");
        request.setShippingArea("LAGOS");

        shippingRate = ShippingRate.builder()
                .area("LAGOS").fee(new BigDecimal("4000")).active(true).build();
    }

    @Test
    void checkout_success_createsOrderAndDeductsStock() {
        Order savedOrder = Order.builder()
                .id(1L).orderNumber("ORD-TEST1234").business(business)
                .customerName("John Doe").email("john@example.com")
                .phoneNumber("08012345678").deliveryAddress("123 Street")
                .shippingArea("LAGOS").shippingFee(new BigDecimal("4000"))
                .totalAmount(new BigDecimal("6000"))
                .status(OrderStatus.PENDING).paymentStatus("PENDING")
                .createdAt(LocalDateTime.now()).build();

        when(cartRepository.findBySessionIdAndBusiness_Id("session-1", 1L))
                .thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCart_Id(1L))
                .thenReturn(List.of(cartItem));
        when(shippingService.getShippingRate("LAGOS"))
                .thenReturn(shippingRate);
        when(orderRepository.save(any(Order.class))).thenReturn(savedOrder);
        when(productRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(product));
        when(orderItemRepository.findByOrder_Id(1L)).thenReturn(List.of(
                OrderItem.builder().id(1L).order(savedOrder).product(product)
                        .quantity(2).price(new BigDecimal("1000"))
                        .subtotal(new BigDecimal("2000")).build()));
        doNothing().when(cartService).clearCart(any(Cart.class));

        OrderResponse response = checkoutService.checkout(request);

        assertEquals("ORD-TEST1234", response.getOrderNumber());
        assertEquals(0, new BigDecimal("6000").compareTo(response.getTotalAmount()));
        assertEquals(OrderStatus.PENDING, response.getStatus());
        assertEquals(1, response.getItems().size());
        assertEquals("Widget", response.getItems().get(0).getProductName());
        assertEquals(2, response.getItems().get(0).getQuantity());

        verify(productRepository).findByIdForUpdate(1L);
        verify(productRepository).save(productCaptor.capture());
        assertEquals(8, productCaptor.getValue().getStock());
        verify(cartService).clearCart(cart);
    }

    @Test
    void checkout_insufficientStock_throwsException() {
        product.setStock(1);

        when(cartRepository.findBySessionIdAndBusiness_Id("session-1", 1L))
                .thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCart_Id(1L))
                .thenReturn(List.of(cartItem));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> checkoutService.checkout(request));
        assertTrue(ex.getMessage().contains("out of stock"));

        verify(orderRepository, never()).save(any());
        verify(productRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void checkout_emptyCart_throwsException() {
        when(cartRepository.findBySessionIdAndBusiness_Id("session-1", 1L))
                .thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCart_Id(1L))
                .thenReturn(List.of());

        assertThrows(BusinessException.class,
                () -> checkoutService.checkout(request));
        verify(orderRepository, never()).save(any());
    }

    @Test
    void checkout_cartNotFound_throwsException() {
        when(cartRepository.findBySessionIdAndBusiness_Id("session-1", 1L))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> checkoutService.checkout(request));
    }

    @Test
    void checkout_stockDeductedViaLockedRead() {
        when(cartRepository.findBySessionIdAndBusiness_Id("session-1", 1L))
                .thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCart_Id(1L))
                .thenReturn(List.of(cartItem));
        when(shippingService.getShippingRate("LAGOS"))
                .thenReturn(shippingRate);
        when(orderRepository.save(any(Order.class))).thenReturn(
                Order.builder().id(1L).orderNumber("ORD-TEST1234")
                        .business(business).createdAt(LocalDateTime.now()).build());
        when(productRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(product));
        when(orderItemRepository.findByOrder_Id(1L)).thenReturn(List.of());
        doNothing().when(cartService).clearCart(any(Cart.class));

        checkoutService.checkout(request);

        verify(productRepository, times(1)).findByIdForUpdate(1L);
        verify(productRepository, times(1)).save(product);
    }
}
