package com.example.shopsecure.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.util.*;

@Service
@RequiredArgsConstructor
public class PaystackService {

    @Value("${paystack.secret-key}")
    private String secretKey;

    private static final String BASE_URL = "https://api.paystack.co";

    private final RestTemplate restTemplate;

    // =========================
    // CUSTOMER PAYMENT
    // =========================
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
        body.put("amount", amount.multiply(BigDecimal.valueOf(100)).intValue());
        body.put("reference", reference);

        HttpEntity<Map<String, Object>> request =
                new HttpEntity<>(body, headers);

        ResponseEntity<Map> response =
                restTemplate.postForEntity(
                        BASE_URL + "/transaction/initialize",
                        request,
                        Map.class
                );

        return (Map<String, Object>) response.getBody().get("data");
    }

    // =========================
    // ACCOUNT RESOLUTION
    // =========================
    public Map<String, Object> resolveAccount(
            String accountNumber,
            String bankCode
    ) {

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(secretKey);

        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<Map> response =
                restTemplate.exchange(
                        BASE_URL + "/bank/resolve?account_number="
                                + accountNumber
                                + "&bank_code="
                                + bankCode,
                        HttpMethod.GET,
                        request,
                        Map.class
                );

        return (Map<String, Object>) response.getBody().get("data");
    }

    // =========================
    // CREATE RECIPIENT
    // =========================
    public Map<String, Object> createTransferRecipient(
            String accountName,
            String accountNumber,
            String bankCode
    ) {

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(secretKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("type", "nuban");
        body.put("name", accountName);
        body.put("account_number", accountNumber);
        body.put("bank_code", bankCode);
        body.put("currency", "NGN");

        HttpEntity<Map<String, Object>> request =
                new HttpEntity<>(body, headers);

        ResponseEntity<Map> response =
                restTemplate.postForEntity(
                        BASE_URL + "/transferrecipient",
                        request,
                        Map.class
                );

        return (Map<String, Object>) response.getBody().get("data");
    }

    // =========================
    // INITIATE TRANSFER (WITHDRAWAL)
    // =========================
    public Map<String, Object> initiateTransfer(
            String recipientCode,
            BigDecimal amount,
            String reference,
            String reason
    ) {

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(secretKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("source", "balance");
        body.put("amount", amount.multiply(BigDecimal.valueOf(100)).intValue());
        body.put("recipient", recipientCode);
        body.put("reason", reason);
        body.put("reference", reference);

        HttpEntity<Map<String, Object>> request =
                new HttpEntity<>(body, headers);

        ResponseEntity<Map> response =
                restTemplate.postForEntity(
                        BASE_URL + "/transfer",
                        request,
                        Map.class
                );

        return (Map<String, Object>) response.getBody().get("data");
    }

    // =========================
    // WEBHOOK VERIFICATION
    // =========================
    public boolean verifyWebhookSignature(
            String payload,
            String signature
    ) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            SecretKeySpec secretKeySpec =
                    new SecretKeySpec(secretKey.getBytes(), "HmacSHA512");

            mac.init(secretKeySpec);

            byte[] hash = mac.doFinal(payload.getBytes());

            String computed =
                    HexFormat.of().formatHex(hash);

            return computed.equals(signature);

        } catch (Exception e) {
            return false;
        }
    }
}