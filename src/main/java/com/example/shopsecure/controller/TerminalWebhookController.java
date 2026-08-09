package com.example.shopsecure.controller;

import com.example.shopsecure.provider.common.WebhookPayload;
import com.example.shopsecure.service.OrderShippingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/webhooks/terminal")
@RequiredArgsConstructor
public class TerminalWebhookController {

    private final OrderShippingService shippingService;

    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    public void receive(@RequestBody String rawBody, @RequestHeader HttpHeaders headers) {
        shippingService.handleWebhook(new WebhookPayload(rawBody, headers.toSingleValueMap()));
    }
}
