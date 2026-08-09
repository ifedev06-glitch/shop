package com.example.shopsecure.controller;

import com.example.shopsecure.provider.common.WebhookPayload;
import com.example.shopsecure.service.PaystackWebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/webhooks/paystack")
@RequiredArgsConstructor
public class PaystackWebhookController {

    private final PaystackWebhookService webhookService;

    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    public void receive(@RequestBody String rawBody, @RequestHeader HttpHeaders headers) {
        webhookService.handle(new WebhookPayload(rawBody, headers.toSingleValueMap()));
    }
}
