package com.example.shopsecure.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PaystackService {

    @Value("${paystack.secret-key}")
    private String secretKey;

    private static final String PAYSTACK_BASE_URL = "https://api.paystack.co";

    private final RestTemplate restTemplate;

    // Called when customer wants to pay — gets the URL to redirect them to
    public Map<String, Object> initializeTransaction(
            String email,
            BigDecimal amount,
            String reference
    ) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(secretKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("amount", amount.multiply(BigDecimal.valueOf(100)).intValue()); // convert to kobo
        body.put("reference", reference);

        HttpEntity<Map<String, Object>> entity =
                new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = restTemplate.postForEntity(
                PAYSTACK_BASE_URL + "/transaction/initialize",
                entity,
                Map.class
        );

        return (Map<String, Object>) response.getBody().get("data");
    }

    // Verifies webhook is actually from Paystack and not a random POST
    public boolean verifyWebhookSignature(
            String payload,
            String paystackSignature
    ) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    secretKey.getBytes(), "HmacSHA512"
            );
            mac.init(secretKeySpec);
            byte[] hashBytes = mac.doFinal(payload.getBytes());
            String computedHash = HexFormat.of()
                    .formatHex(hashBytes);
            return computedHash.equals(paystackSignature);
        } catch (Exception e) {
            return false;
        }
    }
}