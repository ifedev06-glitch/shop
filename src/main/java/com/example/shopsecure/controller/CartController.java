package com.example.shopsecure.controller;

import com.example.shopsecure.dto.request.AddToCartRequest;
import com.example.shopsecure.dto.response.CartResponse;
import com.example.shopsecure.dto.request.UpdateCartItemRequest;
import com.example.shopsecure.service.CartService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
@Slf4j
public class CartController {

    private final CartService cartService;

    @PostMapping("/add")
    public CartResponse addToCart(
            @RequestBody AddToCartRequest request
    ) {
        log.info("Add to cart: sessionId={}, productId={}, quantity={}",
                request.getSessionId(), request.getProductId(), request.getQuantity());
        return cartService.addToCart(request);
    }

    @GetMapping
    public CartResponse getCart(
            @RequestParam String sessionId,
            @RequestParam Long businessId
    ) {
        log.info("Get cart: sessionId={}, businessId={}", sessionId, businessId);
        return cartService.getCart(sessionId, businessId);
    }

    @PutMapping("/item")
    public CartResponse updateCartItem(
            @RequestBody UpdateCartItemRequest request
    ) {
        log.info("Update cart item: sessionId={}, businessId={}, productId={}, quantity={}",
                request.getSessionId(), request.getBusinessId(), request.getProductId(), request.getQuantity());
        return cartService.updateCartItem(
                request.getSessionId(),
                request.getBusinessId(),
                request.getProductId(),
                request.getQuantity()
        );
    }

    @DeleteMapping("/item")
    public void removeCartItem(
            @RequestParam String sessionId,
            @RequestParam Long businessId,
            @RequestParam Long productId
    ) {
        log.info("Remove cart item: sessionId={}, businessId={}, productId={}", sessionId, businessId, productId);
        cartService.removeCartItem(
                sessionId,
                businessId,
                productId
        );
    }
}
