package com.example.shopsecure.provider.common;

import java.util.Map;

public record WebhookPayload(
        String rawBody,
        Map<String, String> headers
) {
    public WebhookPayload {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public String header(String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}
