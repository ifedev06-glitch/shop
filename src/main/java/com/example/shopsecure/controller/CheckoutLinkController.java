package com.example.shopsecure.controller;

import com.example.shopsecure.dto.request.CheckoutCustomerRequest;
import com.example.shopsecure.dto.request.CreateCheckoutLinkRequest;
import com.example.shopsecure.dto.response.CheckoutLinkDetailsResponse;
import com.example.shopsecure.dto.response.CheckoutPaymentResponse;
import com.example.shopsecure.dto.response.CreateCheckoutLinkResponse;
import com.example.shopsecure.service.CheckoutLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class CheckoutLinkController {

    private final CheckoutLinkService checkoutLinkService;

    @PostMapping("/api/vendor/checkout-links")
    public CreateCheckoutLinkResponse createCheckoutLink(
            @RequestBody CreateCheckoutLinkRequest request
    ) {
        return checkoutLinkService.createCheckoutLink(request);
    }

    @GetMapping("/api/checkout-links/{slug}")
    public CheckoutLinkDetailsResponse getCheckoutLink(
            @PathVariable String slug
    ) {
        return checkoutLinkService.getCheckoutLink(slug);
    }

    @PostMapping("/api/checkout-links/{slug}/pay")
    public CheckoutPaymentResponse payForCheckoutLink(
            @PathVariable String slug,
            @RequestBody CheckoutCustomerRequest request
    ) {
        return checkoutLinkService.payForCheckoutLink(
                slug,
                request
        );
    }
}