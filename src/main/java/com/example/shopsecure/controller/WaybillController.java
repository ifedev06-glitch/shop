package com.example.shopsecure.controller;

import com.example.shopsecure.dto.request.CancelWaybillRequest;
import com.example.shopsecure.dto.request.CreateWaybillRequest;
import com.example.shopsecure.dto.request.SelectWaybillQuoteRequest;
import com.example.shopsecure.dto.response.CreateWaybillResponse;
import com.example.shopsecure.dto.response.PageResponse;
import com.example.shopsecure.dto.response.WaybillCancellationResponse;
import com.example.shopsecure.dto.response.WaybillCheckoutResponse;
import com.example.shopsecure.dto.response.WaybillDetailsResponse;
import com.example.shopsecure.dto.response.WaybillInvitationReissueResponse;
import com.example.shopsecure.dto.response.WaybillQuoteResponse;
import com.example.shopsecure.dto.response.WaybillQuoteSelectionResponse;
import com.example.shopsecure.dto.response.WaybillShipmentResponse;
import com.example.shopsecure.dto.response.WaybillStatusHistoryResponse;
import com.example.shopsecure.service.WaybillCancellationService;
import com.example.shopsecure.service.WaybillCheckoutService;
import com.example.shopsecure.service.WaybillService;
import com.example.shopsecure.service.WaybillShippingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/waybills")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class WaybillController {

    private final WaybillService waybillService;
    private final WaybillShippingService shippingService;
    private final WaybillCheckoutService checkoutService;
    private final WaybillCancellationService cancellationService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateWaybillResponse create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateWaybillRequest request
    ) {
        return waybillService.create(request, idempotencyKey);
    }

    @GetMapping
    public PageResponse<WaybillDetailsResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return waybillService.list(page, size);
    }

    @GetMapping("/{publicId}")
    public WaybillDetailsResponse get(@PathVariable String publicId) {
        return waybillService.get(publicId);
    }

    @GetMapping("/{publicId}/history")
    public List<WaybillStatusHistoryResponse> history(@PathVariable String publicId) {
        return waybillService.history(publicId);
    }

    @PostMapping("/{publicId}/invitations/reissue")
    public WaybillInvitationReissueResponse reissueInvitation(@PathVariable String publicId) {
        return waybillService.reissueInvitation(publicId);
    }

    @PostMapping("/{publicId}/shipping/quotes")
    public List<WaybillQuoteResponse> createQuotes(@PathVariable String publicId) {
        return shippingService.createQuotes(publicId);
    }

    @PutMapping("/{publicId}/shipping/selection")
    public WaybillQuoteSelectionResponse selectQuote(
            @PathVariable String publicId,
            @Valid @RequestBody SelectWaybillQuoteRequest request
    ) {
        return shippingService.selectQuote(publicId, request.quoteId());
    }

    @PostMapping("/{publicId}/payments/checkout")
    public WaybillCheckoutResponse initializeCheckout(
            @PathVariable String publicId,
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return checkoutService.initializeCreator(publicId, idempotencyKey);
    }

    @GetMapping("/{publicId}/shipping/tracking")
    public WaybillShipmentResponse refreshTracking(@PathVariable String publicId) {
        return shippingService.refreshTracking(publicId);
    }

    @PostMapping("/{publicId}/cancel")
    public WaybillCancellationResponse cancel(
            @PathVariable String publicId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CancelWaybillRequest request
    ) {
        return cancellationService.request(publicId, idempotencyKey, request.reason());
    }
}
