package com.example.shopsecure.provider.payment;

import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.PaymentException;
import com.example.shopsecure.provider.common.WebhookPayload;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class PaystackPaymentProvider implements PaymentProvider, PayoutProvider {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() { };
    private static final Pattern TRANSFER_REFERENCE = Pattern.compile("^[a-z0-9_-]{16,50}$");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String secretKey;
    private final String baseUrl;
    private final String preferredVirtualAccountBank;

    public PaystackPaymentProvider(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${paystack.secret-key}") String secretKey,
            @Value("${paystack.base-url:https://api.paystack.co}") String baseUrl,
            @Value("${paystack.virtual-account-bank:}") String preferredVirtualAccountBank
    ) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.secretKey = secretKey;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.preferredVirtualAccountBank = preferredVirtualAccountBank;
    }

    @Override
    public PaymentInitResponse initializeTransaction(PaymentRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("amount", toMinorUnits(request.amount()));
        body.put("email", request.customerEmail());
        body.put("reference", request.reference());

        Map<String, Object> data = data(post("/transaction/initialize", body));
        return new PaymentInitResponse(
                requiredString(data, "authorization_url"),
                stringValue(data.getOrDefault("reference", request.reference()))
        );
    }

    @Override
    public PaymentVerificationResult verifyTransaction(String reference) {
        String encodedReference = UriUtils.encodePathSegment(reference, StandardCharsets.UTF_8);
        Map<String, Object> data = data(get("/transaction/verify/" + encodedReference));
        return new PaymentVerificationResult(
                toStatus(stringValue(data.get("status"))),
                fromMinorUnits(data.get("amount")),
                stringValue(data.getOrDefault("reference", reference))
        );
    }

    @Override
    public VirtualAccountResponse createVirtualAccount(VirtualAccountRequest request) {
        String customerCode = findOrCreateCustomer(request);

        Map<String, Object> accountRequest = new HashMap<>();
        accountRequest.put("customer", customerCode);
        accountRequest.put("country", "NG");
        if (preferredVirtualAccountBank != null && !preferredVirtualAccountBank.isBlank()) {
            accountRequest.put("preferred_bank", preferredVirtualAccountBank);
        }

        Map<String, Object> data = data(post("/dedicated_account", accountRequest));
        Map<String, Object> bank = mapValue(data.get("bank"));
        return new VirtualAccountResponse(
                requiredString(data, "account_number"),
                requiredString(bank, "name"),
                requiredString(data, "account_name"),
                parseInstant(data.get("expires_at"))
        );
    }

    @Override
    public PaymentStatusUpdatedEvent handleWebhookEvent(WebhookPayload payload) {
        String signature = payload.header("x-paystack-signature");
        if (!isValidSignature(payload.rawBody(), signature)) {
            throw new PaymentException("Invalid webhook signature");
        }

        try {
            JsonNode root = objectMapper.readTree(payload.rawBody());
            String eventType = root.path("event").asText();
            if (!"charge.success".equals(eventType)
                    && !"charge.failed".equals(eventType)
                    && !"charge.abandoned".equals(eventType)) {
                return PaymentStatusUpdatedEvent.ignored();
            }

            JsonNode eventData = root.path("data");
            PaymentTransactionStatus status = switch (eventType) {
                case "charge.success" -> PaymentTransactionStatus.SUCCESS;
                case "charge.failed" -> PaymentTransactionStatus.FAILED;
                case "charge.abandoned" -> PaymentTransactionStatus.ABANDONED;
                default -> PaymentTransactionStatus.UNKNOWN;
            };
            return new PaymentStatusUpdatedEvent(
                    eventData.path("reference").asText(),
                    status,
                    fromMinorUnits(eventData.path("amount").numberValue()),
                    parseInstant(eventData.path("paid_at").asText(null)),
                    true
            );
        } catch (PaymentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new PaymentException("Invalid payment webhook payload");
        }
    }

    @Override
    public ResolvedBankAccount resolveBankAccount(BankAccountLookupRequest request) {
        String accountNumber = UriUtils.encodeQueryParam(request.accountNumber(), StandardCharsets.UTF_8);
        String bankCode = UriUtils.encodeQueryParam(request.bankCode(), StandardCharsets.UTF_8);
        Map<String, Object> data = data(get(
                "/bank/resolve?account_number=" + accountNumber + "&bank_code=" + bankCode
        ));
        return new ResolvedBankAccount(
                requiredString(data, "account_name"),
                stringValue(data.getOrDefault("account_number", request.accountNumber())),
                request.bankCode(),
                stringValue(data.get("bank_name"))
        );
    }

    @Override
    public PayoutRecipientResponse createPayoutRecipient(PayoutRecipientRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "nuban");
        body.put("name", request.accountName());
        body.put("account_number", request.accountNumber());
        body.put("bank_code", request.bankCode());
        body.put("currency", "NGN");
        Map<String, Object> data = data(post("/transferrecipient", body));
        return new PayoutRecipientResponse(requiredString(data, "recipient_code"));
    }

    @Override
    public PayoutResponse initiatePayout(PayoutRequest request) {
        validateTransferReference(request.reference());
        Map<String, Object> body = new HashMap<>();
        body.put("source", "balance");
        body.put("amount", toMinorUnits(request.amount()));
        body.put("recipient", request.recipientId());
        body.put("reference", request.reference());
        body.put("reason", request.description());
        Map<String, Object> data = data(post("/transfer", body));
        return new PayoutResponse(
                requiredString(data, "transfer_code"),
                stringValue(data.getOrDefault("reference", request.reference())),
                toPayoutStatus(stringValue(data.get("status")))
        );
    }

    @Override
    public PayoutStatusUpdatedEvent verifyPayout(String reference) {
        validateTransferReference(reference);
        String encodedReference = UriUtils.encodePathSegment(reference, StandardCharsets.UTF_8);
        Map<String, Object> data = data(get("/transfer/verify/" + encodedReference));
        return new PayoutStatusUpdatedEvent(
                stringValue(data.getOrDefault("reference", reference)),
                stringValue(data.get("transfer_code")),
                toPayoutStatus(stringValue(data.get("status"))),
                parseInstant(firstValue(data, "transferred_at", "updatedAt", "updated_at")),
                true
        );
    }

    @Override
    public PayoutStatusUpdatedEvent handlePayoutWebhookEvent(WebhookPayload payload) {
        String signature = payload.header("x-paystack-signature");
        if (!isValidSignature(payload.rawBody(), signature)) {
            throw new PaymentException("Invalid webhook signature");
        }

        try {
            JsonNode root = objectMapper.readTree(payload.rawBody());
            String eventType = root.path("event").asText();
            PayoutStatus status = switch (eventType) {
                case "transfer.success" -> PayoutStatus.SUCCESS;
                case "transfer.failed" -> PayoutStatus.FAILED;
                case "transfer.reversed" -> PayoutStatus.REVERSED;
                default -> null;
            };
            if (status == null) {
                return PayoutStatusUpdatedEvent.ignored();
            }

            JsonNode eventData = root.path("data");
            return new PayoutStatusUpdatedEvent(
                    eventData.path("reference").asText(),
                    eventData.path("transfer_code").asText(null),
                    status,
                    parseInstant(firstText(eventData, "transferred_at", "updatedAt", "updated_at")),
                    true
            );
        } catch (PaymentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new PaymentException("Invalid payout webhook payload");
        }
    }

    private String findOrCreateCustomer(VirtualAccountRequest request) {
        String encodedEmail = UriUtils.encodePathSegment(request.email(), StandardCharsets.UTF_8);
        try {
            Map<String, Object> existing = data(get("/customer/" + encodedEmail));
            return requiredString(existing, "customer_code");
        } catch (HttpClientErrorException.NotFound notFound) {
            String[] names = splitName(request.customerName());
            Map<String, Object> customerRequest = new HashMap<>();
            customerRequest.put("email", request.email());
            customerRequest.put("first_name", names[0]);
            customerRequest.put("last_name", names[1]);
            customerRequest.put("phone", request.phone());
            customerRequest.put("metadata", Map.of("customer_reference", request.customerReference()));
            Map<String, Object> created = data(post("/customer", customerRequest));
            return requiredString(created, "customer_code");
        }
    }

    private Map<String, Object> get(String path) {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                baseUrl + path,
                HttpMethod.GET,
                new HttpEntity<>(headers()),
                MAP_TYPE
        );
        return requireBody(response);
    }

    private Map<String, Object> post(String path, Map<String, Object> body) {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                baseUrl + path,
                HttpMethod.POST,
                new HttpEntity<>(body, headers()),
                MAP_TYPE
        );
        return requireBody(response);
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(secretKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private Map<String, Object> requireBody(ResponseEntity<Map<String, Object>> response) {
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new PaymentException("Payment provider returned an invalid response");
        }
        return response.getBody();
    }

    private Map<String, Object> data(Map<String, Object> response) {
        Object status = response.get("status");
        if (status instanceof Boolean success && !success) {
            throw new PaymentException(stringValue(response.getOrDefault("message", "Payment provider request failed")));
        }
        Map<String, Object> data = mapValue(response.get("data"));
        if (data.isEmpty()) {
            throw new PaymentException("Payment provider response did not contain data");
        }
        return data;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private String requiredString(Map<String, Object> source, String key) {
        String value = stringValue(source.get(key));
        if (value == null || value.isBlank()) {
            throw new PaymentException("Payment provider response is missing " + key);
        }
        return value;
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private Object firstValue(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null && !value.toString().isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String firstText(JsonNode source, String... keys) {
        for (String key : keys) {
            String value = source.path(key).asText(null);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private long toMinorUnits(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException("Payment amount must be greater than zero");
        }
        try {
            return amount.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (ArithmeticException exception) {
            throw new BusinessException("Payment amount must have at most two decimal places");
        }
    }

    private void validateTransferReference(String reference) {
        if (reference == null || !TRANSFER_REFERENCE.matcher(reference).matches()) {
            throw new BusinessException("Payout reference must be 16-50 lowercase letters, digits, dashes, or underscores");
        }
    }

    private BigDecimal fromMinorUnits(Object amount) {
        if (amount == null) {
            return null;
        }
        return new BigDecimal(amount.toString()).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY);
    }

    private PaymentTransactionStatus toStatus(String status) {
        if (status == null) {
            return PaymentTransactionStatus.UNKNOWN;
        }
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "success" -> PaymentTransactionStatus.SUCCESS;
            case "failed" -> PaymentTransactionStatus.FAILED;
            case "abandoned" -> PaymentTransactionStatus.ABANDONED;
            case "pending", "ongoing", "processing", "queued" -> PaymentTransactionStatus.PENDING;
            default -> PaymentTransactionStatus.UNKNOWN;
        };
    }

    private PayoutStatus toPayoutStatus(String status) {
        if (status == null) {
            return PayoutStatus.PENDING;
        }
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "success" -> PayoutStatus.SUCCESS;
            case "failed" -> PayoutStatus.FAILED;
            case "reversed" -> PayoutStatus.REVERSED;
            case "otp" -> PayoutStatus.REQUIRES_ACTION;
            case "pending", "received", "queued" -> PayoutStatus.PENDING;
            default -> PayoutStatus.UNKNOWN;
        };
    }

    private String[] splitName(String customerName) {
        String normalized = customerName == null ? "Customer" : customerName.trim();
        if (normalized.isBlank()) {
            normalized = "Customer";
        }
        String[] parts = normalized.split("\\s+", 2);
        return new String[]{parts[0], parts.length > 1 ? parts[1] : parts[0]};
    }

    private Instant parseInstant(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.toString());
        } catch (Exception exception) {
            return null;
        }
    }

    private boolean isValidSignature(String body, String signature) {
        if (body == null || signature == null || signature.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            String expected = HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.US_ASCII),
                    signature.getBytes(StandardCharsets.US_ASCII)
            );
        } catch (Exception exception) {
            return false;
        }
    }
}
