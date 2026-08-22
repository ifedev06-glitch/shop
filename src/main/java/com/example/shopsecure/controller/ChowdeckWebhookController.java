package com.example.shopsecure.controller;

import com.example.shopsecure.dto.request.ChowdeckWebhookRequest;
import com.example.shopsecure.service.ChowdeckWebhookService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/webhooks/chowdeck")
@RequiredArgsConstructor
public class ChowdeckWebhookController {

    private final ChowdeckWebhookService webhookService;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public void receive(@Valid @RequestBody ChowdeckWebhookRequest request) {
        webhookService.handle(request.payload().reference());
    }
}
