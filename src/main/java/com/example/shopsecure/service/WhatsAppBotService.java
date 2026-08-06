package com.example.shopsecure.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class WhatsAppBotService {

    private final RestTemplate restTemplate;

    @Value("${whatsapp.bot.url}")
    private String botUrl;

    @Value("${whatsapp.bot.secret}")
    private String botSecret;

    public void sendWelcome(String phoneNumber, String businessName) {
        post("/send-welcome", Map.of(
                "phone_number", phoneNumber,
                "business_name", businessName
        ));
    }

    public void sendOtp(String phoneNumber, String code) {
        post("/send-otp", Map.of(
                "phone_number", phoneNumber,
                "code", code,
                "expires_in_minutes", "5"
        ));
    }

    private void post(String path, Map<String, String> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(botSecret);
        restTemplate.postForEntity(
                botUrl + path,
                new HttpEntity<>(body, headers),
                String.class
        );
    }
}
