package com.example.shopsecure.provider.payment;

import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.PaymentException;
import com.example.shopsecure.exception.PayoutRejectedException;
import com.example.shopsecure.provider.common.WebhookPayload;
import com.example.shopsecure.util.NigerianPhoneNumber;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
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
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@Profile("!loadtest")
@Slf4j
public class PaystackPaymentProvider implements PaymentProvider, PayoutProvider, RefundProvider {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() { };
    private static final Pattern TRANSFER_REFERENCE = Pattern.compile("^[a-z0-9_-]{16,50}$");
    private static final String TEST_ACCOUNT_RESOLUTION_BANK_CODE = "001";
    private static final String TEST_TRANSFER_BANK_CODE = "057";

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
        body.put("currency", request.currency());
        if (request.callbackUrl() != null && !request.callbackUrl().isBlank()) {
            body.put("callback_url", request.callbackUrl());
        }
        if (request.metadata() != null && !request.metadata().isEmpty()) {
            body.put("metadata", request.metadata());
        }

        Map<String, Object> data = data(post("/transaction/initialize", body));
        return new PaymentInitResponse(
                requiredString(data, "authorization_url"),
                requiredString(data, "access_code"),
                requiredString(data, "reference")
        );
    }

    @Override
    public PaymentVerificationResult verifyTransaction(String reference) {
        String encodedReference = UriUtils.encodePathSegment(reference, StandardCharsets.UTF_8);
        Map<String, Object> data = data(get("/transaction/verify/" + encodedReference));
        return new PaymentVerificationResult(
                stringValue(data.get("id")),
                requiredString(data, "reference"),
                toStatus(stringValue(data.get("status"))),
                fromMinorUnits(data.get("amount")),
                stringValue(data.get("currency")),
                stringValue(data.get("channel")),
                parseInstant(firstValue(data, "paid_at", "created_at"))
        );
    }

    @Override
    public VirtualAccountResponse createVirtualAccount(VirtualAccountRequest request) {
        if (request.phone() == null || request.phone().isBlank()) {
            throw new BusinessException("Phone number is required to create a virtual account");
        }
        VirtualAccountRequest normalizedRequest = new VirtualAccountRequest(
                request.customerName(),
                request.email(),
                NigerianPhoneNumber.normalize(request.phone()),
                request.customerReference()
        );
        Map<String, Object> customer = findOrCreateCustomer(normalizedRequest);
        Map<String, Object> existingAccount = mapValue(customer.get("dedicated_account"));
        if (!existingAccount.isEmpty()) {
            return toVirtualAccountResponse(existingAccount);
        }
        String customerCode = requiredString(customer, "customer_code");
        String[] names = splitName(request.customerName());

        Map<String, Object> accountRequest = new HashMap<>();
        accountRequest.put("customer", customerCode);
        accountRequest.put("first_name", names[0]);
        accountRequest.put("last_name", names[1]);
        accountRequest.put("phone", normalizedRequest.phone());
        if (preferredVirtualAccountBank != null && !preferredVirtualAccountBank.isBlank()) {
            accountRequest.put("preferred_bank", preferredVirtualAccountBank);
        }

        return toVirtualAccountResponse(data(post("/dedicated_account", accountRequest)));
    }

    private VirtualAccountResponse toVirtualAccountResponse(Map<String, Object> data) {
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
            String eventId = firstText(eventData, "id");
            String reference = firstText(eventData, "reference");
            JsonNode amountNode = eventData.path("amount");
            BigDecimal amount = fromMinorUnits(amountNode.isNumber() ? amountNode.numberValue() : null);
            String currency = firstText(eventData, "currency");
            String channel = firstNonBlank(
                    firstText(eventData, "channel"),
                    firstText(eventData.path("authorization"), "channel")
            );
            String receiverAccountNumber = firstNonBlank(
                    firstText(
                            eventData.path("authorization"),
                            "receiver_bank_account_number",
                            "receiver_account_number"
                    ),
                    firstText(eventData.path("dedicated_account"), "account_number")
            );
            Instant occurredAt = parseInstant(firstText(eventData, "paid_at", "created_at"));

            if (reference == null || reference.isBlank()) {
                throw new PaymentException("Invalid payment webhook payload");
            }
            if (status == PaymentTransactionStatus.SUCCESS
                    && (amount == null || currency == null || currency.isBlank())) {
                throw new PaymentException("Invalid payment webhook payload");
            }

            return new PaymentStatusUpdatedEvent(
                    eventId,
                    reference,
                    status,
                    amount,
                    currency,
                    channel,
                    receiverAccountNumber,
                    occurredAt,
                    true
            );
        } catch (PaymentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new PaymentException("Invalid payment webhook payload");
        }
    }

    @Override
    public RefundResult initiateRefund(RefundRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("transaction", request.transactionIdOrReference());
        body.put("amount", toRefundMinorUnits(request.amount()));
        body.put("currency", request.currency());
        body.put("customer_note", request.customerNote());
        body.put("merchant_note", request.merchantNote());
        return toRefundResult(data(post("/refund", body)));
    }

    @Override
    public RefundResult verifyRefund(String refundId) {
        String encodedRefundId = UriUtils.encodePathSegment(refundId, StandardCharsets.UTF_8);
        return toRefundResult(data(get("/refund/" + encodedRefundId)));
    }

    @Override
    public RefundStatusUpdatedEvent handleRefundWebhookEvent(WebhookPayload payload) {
        String signature = payload.header("x-paystack-signature");
        if (!isValidSignature(payload.rawBody(), signature)) {
            throw new PaymentException("Invalid webhook signature");
        }

        try {
            JsonNode root = objectMapper.readTree(payload.rawBody());
            String eventType = root.path("event").asText();
            RefundStatus status = switch (eventType) {
                case "refund.pending" -> RefundStatus.PENDING;
                case "refund.processing" -> RefundStatus.PROCESSING;
                case "refund.needs-attention" -> RefundStatus.NEEDS_ATTENTION;
                case "refund.failed" -> RefundStatus.FAILED;
                case "refund.processed" -> RefundStatus.PROCESSED;
                default -> null;
            };
            if (status == null) {
                return RefundStatusUpdatedEvent.ignored();
            }

            JsonNode eventData = root.path("data");
            JsonNode refundData = eventData.path("refund");
            JsonNode transactionData = eventData.path("transaction");
            JsonNode refundTransactionData = refundData.path("transaction");
            Object amount = jsonScalarValue(refundData.path("amount"));
            if (amount == null) {
                amount = jsonScalarValue(eventData.path("amount"));
            }
            if (amount == null) {
                amount = jsonScalarValue(transactionData.path("amount"));
            }

            return new RefundStatusUpdatedEvent(
                    refundEventKey(eventType, payload.rawBody()),
                    firstNonBlank(
                            firstText(eventData, "refund_reference"),
                            firstText(refundData, "refund_reference")
                    ),
                    firstNonBlank(
                            firstText(eventData, "transaction_reference"),
                            firstText(transactionData, "reference", "transaction_reference"),
                            scalarTransactionReference(jsonScalarValue(eventData.path("transaction"))),
                            firstText(refundData, "transaction_reference"),
                            firstText(refundTransactionData, "reference", "transaction_reference"),
                            scalarTransactionReference(jsonScalarValue(refundData.path("transaction")))
                    ),
                    fromRefundMinorUnits(amount),
                    firstNonBlank(
                            firstText(refundData, "currency"),
                            firstText(eventData, "currency"),
                            firstText(transactionData, "currency")
                    ),
                    status,
                    parseInstant(firstNonBlank(
                            firstText(refundData, "updatedAt", "updated_at", "refunded_at", "createdAt", "created_at"),
                            firstText(eventData, "occurred_at", "updatedAt", "updated_at", "createdAt", "created_at")
                    )),
                    true
            );
        } catch (PaymentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new PaymentException("Invalid refund webhook payload");
        }
    }

    @Override
    public List<Bank> listBanks() {
        if (isTestMode()) {
            return List.of(new Bank("Paystack Test Bank", TEST_ACCOUNT_RESOLUTION_BANK_CODE));
        }
        return listData(get("/bank?country=nigeria&currency=NGN&type=nuban")).stream()
                .map(this::mapValue)
                .filter(bank -> Boolean.TRUE.equals(bank.get("active")))
                .map(bank -> new Bank(
                        requiredString(bank, "name"),
                        requiredString(bank, "code")
                ))
                .toList();
    }

    @Override
    public ResolvedBankAccount resolveBankAccount(BankAccountLookupRequest request) {
        if (isTestMode() && !TEST_ACCOUNT_RESOLUTION_BANK_CODE.equals(request.bankCode())) {
            throw new BusinessException("Use Paystack Test Bank while Paystack is in test mode");
        }
        String accountNumber = UriUtils.encodeQueryParam(request.accountNumber(), StandardCharsets.UTF_8);
        String bankCode = UriUtils.encodeQueryParam(request.bankCode(), StandardCharsets.UTF_8);
        Map<String, Object> data = sensitiveData(get(
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
        String bankCode = isTestMode() && TEST_ACCOUNT_RESOLUTION_BANK_CODE.equals(request.bankCode())
                ? TEST_TRANSFER_BANK_CODE
                : request.bankCode();
        Map<String, Object> body = new HashMap<>();
        body.put("type", "nuban");
        body.put("name", request.accountName());
        body.put("account_number", request.accountNumber());
        body.put("bank_code", bankCode);
        body.put("currency", "NGN");
        Map<String, Object> data = sensitiveData(post("/transferrecipient", body));
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
        Map<String, Object> data = payoutData(post("/transfer", body));
        return new PayoutResponse(
                requiredString(data, "transfer_code"),
                stringValue(data.getOrDefault("reference", request.reference())),
                toPayoutStatus(stringValue(data.get("status"))),
                fromMinorUnits(data.get("amount")),
                stringValue(data.get("currency")),
                payoutRecipientId(data.get("recipient"))
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
                fromMinorUnits(data.get("amount")),
                stringValue(data.get("currency")),
                payoutRecipientId(data.get("recipient")),
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
                    fromMinorUnits(eventData.path("amount").isNumber()
                            ? eventData.path("amount").numberValue()
                            : null),
                    eventData.path("currency").asText(null),
                    payoutRecipientId(eventData.path("recipient")),
                    parseInstant(firstText(eventData, "transferred_at", "updatedAt", "updated_at")),
                    true
            );
        } catch (PaymentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new PaymentException("Invalid payout webhook payload");
        }
    }

    private Map<String, Object> findOrCreateCustomer(VirtualAccountRequest request) {
        String encodedEmail = UriUtils.encodePathSegment(request.email(), StandardCharsets.UTF_8);
        try {
            return sensitiveData(get("/customer/" + encodedEmail, true));
        } catch (HttpClientErrorException.NotFound notFound) {
            String[] names = splitName(request.customerName());
            Map<String, Object> customerRequest = new HashMap<>();
            customerRequest.put("email", request.email());
            customerRequest.put("first_name", names[0]);
            customerRequest.put("last_name", names[1]);
            customerRequest.put("phone", request.phone());
            customerRequest.put("metadata", Map.of("customer_reference", request.customerReference()));
            return sensitiveData(post("/customer", customerRequest));
        }
    }

    private Map<String, Object> get(String path) {
        return get(path, false);
    }

    private Map<String, Object> get(String path, boolean preserveNotFound) {
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    baseUrl + path,
                    HttpMethod.GET,
                    new HttpEntity<>(headers()),
                    MAP_TYPE
            );
            return requireBody(response);
        } catch (HttpClientErrorException.NotFound exception) {
            if (preserveNotFound) {
                throw exception;
            }
            throw new PaymentException("Payment provider request failed");
        } catch (HttpStatusCodeException exception) {
            logProviderFailure("GET", path, exception);
            throw new PaymentException("Payment provider request failed");
        } catch (RestClientException exception) {
            throw new PaymentException("Payment provider request failed");
        }
    }

    private Map<String, Object> post(String path, Map<String, Object> body) {
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    baseUrl + path,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers()),
                    MAP_TYPE
            );
            return requireBody(response);
        } catch (HttpStatusCodeException exception) {
            logProviderFailure("POST", path, exception);
            if ("/transfer".equals(path) && isDefinitivePayoutRejection(exception)) {
                throw new PayoutRejectedException();
            }
            throw new PaymentException("Payment provider request failed");
        } catch (RestClientException exception) {
            throw new PaymentException("Payment provider request failed");
        }
    }

    private void logProviderFailure(String method, String path, HttpStatusCodeException exception) {
        boolean sensitiveRequest = path.startsWith("/bank/resolve")
                || path.startsWith("/transferrecipient")
                || path.equals("/customer")
                || path.startsWith("/customer/");
        String message = sensitiveRequest
                ? "Sensitive provider request rejected"
                : "Provider request rejected";
        log.warn("Paystack request failed: method={}, path={}, status={}, message={}",
                method, safeProviderPath(path), exception.getStatusCode().value(), message);
        if (exception.getStatusCode() == HttpStatus.UNAUTHORIZED) {
            logCredentialDiagnostic();
        }
    }

    private void logCredentialDiagnostic() {
        String configuredKey = secretKey == null ? "" : secretKey;
        String trimmedKey = configuredKey.trim();
        String keyMode = trimmedKey.startsWith("sk_live_")
                ? "live"
                : trimmedKey.startsWith("sk_test_") ? "test" : "invalid";
        log.warn("Paystack credential rejected: authorizationScheme=Bearer, keyMode={}, "
                        + "keyLength={}, trimmedLength={}, surroundingWhitespace={}, "
                        + "variableAssignmentPrefix={}, bearerPrefix={}, fingerprint={}",
                keyMode,
                configuredKey.length(),
                trimmedKey.length(),
                !configuredKey.equals(trimmedKey),
                trimmedKey.startsWith("PAYSTACK_SECRET_KEY="),
                trimmedKey.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length()),
                credentialFingerprint(configuredKey));
    }

    private String credentialFingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException exception) {
            return "unavailable";
        }
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
            throw new PaymentException("Payment provider request failed");
        }
        Map<String, Object> data = mapValue(response.get("data"));
        if (data.isEmpty()) {
            throw new PaymentException("Payment provider response did not contain data");
        }
        return data;
    }

    private Map<String, Object> sensitiveData(Map<String, Object> response) {
        if (Boolean.FALSE.equals(response.get("status"))) {
            throw new PaymentException("Payment provider request failed");
        }
        return data(response);
    }

    private Map<String, Object> payoutData(Map<String, Object> response) {
        if (Boolean.FALSE.equals(response.get("status"))) {
            if (isDefinitivePayoutRejectionMessage(stringValue(response.get("message")))) {
                throw new PayoutRejectedException();
            }
            throw new PaymentException("Payment provider request failed");
        }
        return data(response);
    }

    private RefundResult toRefundResult(Map<String, Object> data) {
        Object transactionValue = data.get("transaction");
        Map<String, Object> transaction = mapValue(transactionValue);
        String scalarTransaction = scalarValue(transactionValue);
        return new RefundResult(
                requiredString(data, "id"),
                stringValue(data.get("refund_reference")),
                firstNonBlank(
                        stringValue(transaction.get("id")),
                        stringValue(data.get("transaction_id")),
                        scalarTransactionId(scalarTransaction)
                ),
                firstNonBlank(
                        stringValue(transaction.get("reference")),
                        stringValue(data.get("transaction_reference")),
                        scalarTransactionReference(scalarTransaction)
                ),
                fromRefundMinorUnits(data.get("amount")),
                stringValue(data.get("currency")),
                toRefundStatus(stringValue(data.get("status"))),
                stringValue(data.get("merchant_note")),
                parseInstant(firstValue(data, "updatedAt", "updated_at", "refunded_at", "createdAt", "created_at"))
        );
    }

    private boolean isDefinitivePayoutRejection(HttpStatusCodeException exception) {
        if (exception.getStatusCode() != HttpStatus.BAD_REQUEST
                && exception.getStatusCode() != HttpStatus.UNPROCESSABLE_ENTITY) {
            return false;
        }
        return isDefinitivePayoutRejectionMessage(providerMessage(exception.getResponseBodyAsString()));
    }

    private boolean isDefinitivePayoutRejectionMessage(String message) {
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("insufficient balance")
                || normalized.contains("balance is not enough")
                || normalized.contains("balance is insufficient")
                || normalized.contains("invalid recipient")
                || normalized.contains("recipient does not exist")
                || normalized.contains("recipient not found");
    }

    private String providerMessage(String responseBody) {
        try {
            String message = objectMapper.readTree(responseBody).path("message").asText(null);
            return message == null || message.isBlank() ? null : message;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String safeProviderPath(String path) {
        if (path.startsWith("/customer/")) {
            return "/customer/[redacted]";
        }
        if (path.startsWith("/transaction/verify/")) {
            return "/transaction/verify/[redacted]";
        }
        if (path.startsWith("/transfer/verify/")) {
            return "/transfer/verify/[redacted]";
        }
        if (path.startsWith("/refund/")) {
            return "/refund/[redacted]";
        }
        int queryStart = path.indexOf('?');
        return queryStart < 0 ? path : path.substring(0, queryStart) + "?[redacted]";
    }

    private List<?> listData(Map<String, Object> response) {
        Object status = response.get("status");
        if (status instanceof Boolean success && !success) {
            throw new PaymentException("Payment provider request failed");
        }
        Object data = response.get("data");
        if (!(data instanceof List<?> list)) {
            throw new PaymentException("Payment provider response did not contain data");
        }
        return list;
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

    private String scalarValue(Object value) {
        return value instanceof Map<?, ?> ? null : stringValue(value);
    }

    private String scalarTransactionId(Object value) {
        String text = stringValue(value);
        return text != null && !text.isBlank() && text.chars().allMatch(Character::isDigit) ? text : null;
    }

    private String scalarTransactionReference(Object value) {
        String text = stringValue(value);
        return text != null && !text.isBlank() && !text.chars().allMatch(Character::isDigit) ? text : null;
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

    private String firstNonBlank(String... values) {
        for (String value : values) {
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

    private long toRefundMinorUnits(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException("Refund amount must be greater than zero");
        }
        try {
            return amount.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (ArithmeticException exception) {
            throw new BusinessException("Refund amount must have at most two decimal places");
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

    private String payoutRecipientId(Object recipient) {
        Map<String, Object> recipientData = mapValue(recipient);
        String scalarRecipient = scalarValue(recipient);
        return firstNonBlank(
                stringValue(recipientData.get("recipient_code")),
                stringValue(recipientData.get("recipientCode")),
                scalarRecipient != null && scalarRecipient.toUpperCase(Locale.ROOT).startsWith("RCP_")
                        ? scalarRecipient
                        : null
        );
    }

    private String payoutRecipientId(JsonNode recipient) {
        if (recipient == null || recipient.isNull() || recipient.isMissingNode()) {
            return null;
        }
        if (recipient.isObject()) {
            return firstText(recipient, "recipient_code", "recipientCode");
        }
        String value = recipient.asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    private BigDecimal fromRefundMinorUnits(Object amount) {
        if (amount == null) {
            return null;
        }
        try {
            BigDecimal converted = new BigDecimal(amount.toString())
                    .movePointLeft(2)
                    .setScale(2, RoundingMode.UNNECESSARY);
            if (converted.signum() < 0) {
                throw new ArithmeticException("Negative refund amount");
            }
            return converted;
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new PaymentException("Payment provider returned an invalid refund amount");
        }
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

    private boolean isTestMode() {
        return secretKey.startsWith("sk_test_");
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

    private RefundStatus toRefundStatus(String status) {
        if (status == null) {
            return RefundStatus.UNKNOWN;
        }
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "pending" -> RefundStatus.PENDING;
            case "processing" -> RefundStatus.PROCESSING;
            case "needs-attention", "needs_attention" -> RefundStatus.NEEDS_ATTENTION;
            case "processed" -> RefundStatus.PROCESSED;
            case "failed" -> RefundStatus.FAILED;
            default -> RefundStatus.UNKNOWN;
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

    private Object jsonScalarValue(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull() || value.isObject() || value.isArray()) {
            return null;
        }
        return value.isNumber() ? value.numberValue() : value.asText(null);
    }

    private String refundEventKey(String eventType, String rawBody) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((eventType + rawBody).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
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
