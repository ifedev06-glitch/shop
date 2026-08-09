package com.example.shopsecure.controller;

import com.example.shopsecure.dto.request.*;
import com.example.shopsecure.dto.response.*;
import com.example.shopsecure.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/orders/token/{shareToken}")
@RequiredArgsConstructor
public class PublicOrderController {

    private final OrderService orderService;
    private final OrderShippingService shippingService;
    private final GuestOrderStatusService guestStatusService;
    private final OrderSettlementService settlementService;
    private final OrderCheckoutService checkoutService;

    @GetMapping
    public PublicOrderResponse getPublicOrder(@PathVariable String shareToken) {
        return orderService.getByShareToken(shareToken);
    }

    @PostMapping("/bank-account/resolve")
    public ResolvedBankAccountResponse resolveVendorBank(
            @PathVariable String shareToken,
            @Valid @RequestBody ResolveBankAccountRequest request
    ) {
        return orderService.resolveGuestVendorBank(shareToken, request);
    }

    @PostMapping("/counterparty-info")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public OtpChallengeResponse submitCounterpartyInfo(
            @PathVariable String shareToken,
            @Valid @RequestBody CounterpartyInfoRequest request
    ) {
        return orderService.submitCounterpartyInfo(shareToken, request);
    }

    @PostMapping("/verify-otp")
    public OrderVerificationResponse verifyCounterpartyOtp(
            @PathVariable String shareToken,
            @Valid @RequestBody VerifyOrderOtpRequest request
    ) {
        return orderService.verifyCounterpartyOtp(shareToken, request.challengeId(), request.code());
    }

    @PostMapping("/resend-otp")
    public OtpChallengeResponse resendCounterpartyOtp(
            @PathVariable String shareToken,
            @Valid @RequestBody ResendOrderOtpRequest request
    ) {
        return orderService.resendCounterpartyOtp(shareToken, request.challengeId());
    }

    @PostMapping("/access-recovery-otp")
    public OtpChallengeResponse requestAccessRecovery(@PathVariable String shareToken) {
        return orderService.requestGuestAccessRecovery(shareToken);
    }

    @PostMapping("/recover-access")
    public OrderVerificationResponse recoverAccess(
            @PathVariable String shareToken,
            @Valid @RequestBody VerifyOrderOtpRequest request
    ) {
        return orderService.recoverGuestAccess(shareToken, request.challengeId(), request.code());
    }

    @PostMapping("/shipping/quotes")
    public List<ShippingQuoteResponse> createShippingQuotes(
            @PathVariable String shareToken,
            @RequestHeader("X-Order-Access-Token") String guestToken
    ) {
        return shippingService.createGuestQuotes(shareToken, guestToken);
    }

    @PutMapping("/shipping/selection")
    public ShippingSelectionResponse selectShippingQuote(
            @PathVariable String shareToken,
            @RequestHeader("X-Order-Access-Token") String guestToken,
            @Valid @RequestBody SelectShippingQuoteRequest request
    ) {
        return shippingService.selectGuestQuote(shareToken, guestToken, request.quoteId());
    }

    @PostMapping("/payments/checkout")
    public OrderCheckoutResponse initializeCheckout(
            @PathVariable String shareToken,
            @RequestHeader("X-Order-Access-Token") String guestToken
    ) {
        return checkoutService.initialize(shareToken, guestToken);
    }

    @GetMapping("/status")
    public GuestOrderStatusResponse getStatus(
            @PathVariable String shareToken,
            @RequestHeader("X-Order-Access-Token") String guestToken
    ) {
        return guestStatusService.getStatus(shareToken, guestToken);
    }

    @PostMapping("/delivery-otp")
    public OtpChallengeResponse requestDeliveryOtp(
            @PathVariable String shareToken,
            @RequestHeader("X-Order-Access-Token") String guestToken
    ) {
        return settlementService.requestGuestDeliveryOtp(shareToken, guestToken);
    }

    @PostMapping("/confirm-delivery")
    public DeliveryConfirmationResponse confirmDelivery(
            @PathVariable String shareToken,
            @RequestHeader("X-Order-Access-Token") String guestToken,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody VerifyDeliveryOtpRequest request
    ) {
        return settlementService.confirmGuestDelivery(
                shareToken,
                guestToken,
                request.challengeId(),
                request.code(),
                idempotencyKey
        );
    }
}
