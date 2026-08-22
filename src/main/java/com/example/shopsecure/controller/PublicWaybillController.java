package com.example.shopsecure.controller;

import com.example.shopsecure.dto.request.ResendWaybillOtpRequest;
import com.example.shopsecure.dto.request.VerifyWaybillOtpRequest;
import com.example.shopsecure.dto.response.PublicWaybillInvitationResponse;
import com.example.shopsecure.dto.response.WaybillAccessResponse;
import com.example.shopsecure.dto.response.WaybillCheckoutResponse;
import com.example.shopsecure.dto.response.WaybillOtpChallengeResponse;
import com.example.shopsecure.service.WaybillAccessService;
import com.example.shopsecure.service.WaybillCheckoutService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/waybills/invitations/{invitationToken}")
@RequiredArgsConstructor
public class PublicWaybillController {

    private final WaybillAccessService accessService;
    private final WaybillCheckoutService checkoutService;

    @GetMapping
    public PublicWaybillInvitationResponse getSummary(@PathVariable String invitationToken) {
        return accessService.getInvitation(invitationToken);
    }

    @PostMapping("/payment-access-otp")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public WaybillOtpChallengeResponse requestPaymentAccess(@PathVariable String invitationToken) {
        return accessService.requestPaymentAccess(invitationToken);
    }

    @PostMapping("/verify-otp")
    public WaybillAccessResponse verifyOtp(
            @PathVariable String invitationToken,
            @Valid @RequestBody VerifyWaybillOtpRequest request
    ) {
        return accessService.verify(invitationToken, request.challengeId(), request.code());
    }

    @PostMapping("/resend-otp")
    public WaybillOtpChallengeResponse resendOtp(
            @PathVariable String invitationToken,
            @Valid @RequestBody ResendWaybillOtpRequest request
    ) {
        return accessService.resendOtp(invitationToken, request.challengeId());
    }

    @PostMapping("/access-recovery")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public WaybillOtpChallengeResponse requestAccessRecovery(@PathVariable String invitationToken) {
        return accessService.requestAccessRecovery(invitationToken);
    }

    @PostMapping("/access-recovery/verify")
    public WaybillAccessResponse verifyAccessRecovery(
            @PathVariable String invitationToken,
            @Valid @RequestBody VerifyWaybillOtpRequest request
    ) {
        return accessService.verifyAccessRecovery(
                invitationToken,
                request.challengeId(),
                request.code()
        );
    }

    @GetMapping("/status")
    public WaybillAccessResponse getStatus(
            @PathVariable String invitationToken,
            @RequestHeader("X-Waybill-Access-Token") String accessToken
    ) {
        return accessService.getAccessStatus(invitationToken, accessToken);
    }

    @PostMapping("/payments/checkout")
    public WaybillCheckoutResponse initializeCheckout(
            @PathVariable String invitationToken,
            @RequestHeader("X-Waybill-Access-Token") String accessToken,
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return checkoutService.initializeInvited(invitationToken, accessToken, idempotencyKey);
    }
}
