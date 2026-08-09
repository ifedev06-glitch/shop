package com.example.shopsecure.provider.notification;

import com.example.shopsecure.exception.BusinessException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

public final class TwilioSmsProvider implements SmsProvider {

    private static final String REQUEST_FAILED = "SMS provider request failed";
    private static final String INVALID_RESPONSE = "SMS provider returned an invalid response";
    private static final ParameterizedTypeReference<Map<String, Object>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() { };

    private final RestTemplate restTemplate;
    private final String accountSid;
    private final String authToken;
    private final String fromNumber;
    private final URI messagesUri;

    public TwilioSmsProvider(
            RestTemplate restTemplate,
            String accountSid,
            String authToken,
            String fromNumber,
            String baseUrl
    ) {
        this.restTemplate = Objects.requireNonNull(restTemplate, "RestTemplate is required");
        this.accountSid = requireConfiguration(accountSid, "Twilio account SID is required");
        this.authToken = requireConfiguration(authToken, "Twilio auth token is required");
        this.fromNumber = normalizePhoneNumber(requireConfiguration(
                fromNumber,
                "Twilio sender number is required"
        ));

        String normalizedBaseUrl = requireConfiguration(baseUrl, "Twilio base URL is required")
                .replaceAll("/+$", "");
        String encodedSid = UriUtils.encodePathSegment(this.accountSid, StandardCharsets.UTF_8);
        try {
            this.messagesUri = URI.create(normalizedBaseUrl
                    + "/2010-04-01/Accounts/"
                    + encodedSid
                    + "/Messages.json");
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Twilio base URL is invalid");
        }
    }

    @Override
    public void sendMessage(String phoneNumber, String message) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw new BusinessException("Phone number is required");
        }
        if (message == null || message.isBlank()) {
            throw new BusinessException("Message is required");
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("To", normalizePhoneNumber(phoneNumber));
        form.add("From", fromNumber);
        form.add("Body", message);

        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(accountSid, authToken, StandardCharsets.UTF_8);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    messagesUri,
                    HttpMethod.POST,
                    new HttpEntity<>(form, headers),
                    RESPONSE_TYPE
            );
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new BusinessException(REQUEST_FAILED);
            }

            Map<String, Object> body = response.getBody();
            Object sid = body == null ? null : body.get("sid");
            if (!(sid instanceof String sidValue) || sidValue.isBlank()) {
                throw new BusinessException(INVALID_RESPONSE);
            }
        } catch (RestClientException exception) {
            throw new BusinessException(REQUEST_FAILED);
        }
    }

    private static String normalizePhoneNumber(String phoneNumber) {
        String normalized = phoneNumber.trim();
        if (!normalized.isEmpty() && normalized.chars().allMatch(Character::isDigit)) {
            return "+" + normalized;
        }
        return normalized;
    }

    private static String requireConfiguration(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
