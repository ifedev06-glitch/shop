package com.example.shopsecure.controller;

import com.example.shopsecure.dto.request.*;
import com.example.shopsecure.dto.response.*;
import com.example.shopsecure.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/orders/token/{shareToken}")
@RequiredArgsConstructor
public class PublicOrderController {

    private final OrderService orderService;
    private final GuestOrderStatusService guestStatusService;
    private final OrderSettlementService settlementService;
    private final OrderCheckoutService checkoutService;

    @GetMapping
    public PublicOrderResponse getPublicOrder(@PathVariable String shareToken) {
        return orderService.getByShareToken(shareToken);
    }

    @PostMapping("/payment-access-otp")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public OtpChallengeResponse requestPaymentAccess(@PathVariable String shareToken) {
        return orderService.requestBuyerPaymentAccess(shareToken);
    }

    @PostMapping("/verify-otp")
    public OrderVerificationResponse verifyCounterpartyOtp(
            @PathVariable String shareToken,
            @Valid @RequestBody VerifyOrderOtpRequest request
    ) {
        return orderService.verifyBuyerPaymentOtp(shareToken, request.challengeId(), request.code());
    }

    @PostMapping("/resend-otp")
    public OtpChallengeResponse resendCounterpartyOtp(
            @PathVariable String shareToken,
            @Valid @RequestBody ResendOrderOtpRequest request
    ) {
        return orderService.resendBuyerPaymentOtp(shareToken, request.challengeId());
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
