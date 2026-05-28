package com.example.shopsecure.controller;

import com.example.shopsecure.dto.request.AddToCartRequest;
import com.example.shopsecure.dto.response.CartResponse;
import com.example.shopsecure.dto.request.UpdateCartItemRequest;
import com.example.shopsecure.service.CartService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @PostMapping("/add")
    public CartResponse addToCart(
            @RequestBody AddToCartRequest request
    ) {
        return cartService.addToCart(request);
    }

    @GetMapping
    public CartResponse getCart(
            @RequestParam String sessionId,
            @RequestParam Long businessId
    ) {
        return cartService.getCart(sessionId, businessId);
    }

    @PutMapping("/item")
    public CartResponse updateCartItem(
            @RequestBody UpdateCartItemRequest request
    ) {
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
        cartService.removeCartItem(
                sessionId,
                businessId,
                productId
        );
    }
}
