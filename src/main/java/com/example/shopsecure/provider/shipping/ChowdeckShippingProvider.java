package com.example.shopsecure.provider.shipping;

import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.ServiceUnavailableException;
import com.example.shopsecure.provider.common.WebhookPayload;
import com.example.shopsecure.util.NigerianPhoneNumber;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ChowdeckShippingProvider implements ShippingProvider {

    private static final String REQUEST_FAILED = "Chowdeck request failed";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String secretKey;
    private final String baseUrl;
    private final Duration quoteTtl;
    private final boolean hardDisabled;

    public ChowdeckShippingProvider(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            String secretKey,
            String baseUrl,
            Duration quoteTtl,
            boolean hardDisabled
    ) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.secretKey = secretKey == null ? "" : secretKey.trim();
        this.baseUrl = (baseUrl == null ? "" : baseUrl).replaceAll("/+$", "");
        this.quoteTtl = quoteTtl == null || quoteTtl.isZero() || quoteTtl.isNegative()
                ? Duration.ofMinutes(5)
                : quoteTtl;
        this.hardDisabled = hardDisabled;
    }

    @Override
    public ShippingPreparation prepareShipment(ShipmentRequest request, String currency) {
        requireRequest(request);
        ShippingQuoteResult quoteResult = quoteShipment(request.orderReference(), request, currency);
        return new ShippingPreparation(
                new ShipmentResponse(
                        requiredText(request.orderReference(), "Shipment reference is required"),
                        ShipmentStatus.DRAFT,
                        request.orderReference(),
                        null,
                        null
                ),
                quoteResult
        );
    }

    @Override
    public ShippingQuoteResult quoteShipment(
            String shipmentId,
            ShipmentRequest request,
            String currency
    ) {
        ensureAvailable();
        requireRequest(request);
        if (!"NGN".equalsIgnoreCase(requiredText(currency, "Shipping currency is required"))) {
            throw new BusinessException("Chowdeck supports NGN shipping quotes only");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source_address_string", addressString(request.pickupAddress()));
        body.put("destination_address_string", addressString(request.deliveryAddress()));
        body.put("estimated_order_amount", toMinorUnits(request.parcelDetails().value()));
        JsonNode data = exchange("/relay/delivery/fee", HttpMethod.POST, body);

        String feeId = requiredIntegralText(data, "id");
        BigDecimal providerAmount = fromMinorUnits(requiredLong(data, "total_amount"));
        ShippingRateOption rate = new ShippingRateOption(
                feeId,
                feeId,
                "Chowdeck",
                null,
                "Chowdeck delivery",
                providerAmount,
                "NGN",
                null,
                null
        );
        return new ShippingQuoteResult(List.of(rate), Instant.now().plus(quoteTtl));
    }

    @Override
    public AddressValidationResult validateAddress(ShipmentAddress address) {
        addressString(address);
        return new AddressValidationResult(true, null);
    }

    @Override
    public List<ShippingState> getStates(String countryCode) {
        throw new BusinessException("Chowdeck does not provide a shipping location catalogue");
    }

    @Override
    public List<ShippingCity> getCities(String countryCode) {
        throw new BusinessException("Chowdeck does not provide a shipping location catalogue");
    }

    @Override
    public ShipmentResponse createQuickShipment(ShipmentRequest request) {
        throw new UnsupportedOperationException("Use prepareShipment for Chowdeck");
    }

    @Override
    public List<ShippingRateOption> getRates(String shipmentId, String currency) {
        throw new UnsupportedOperationException("Use quoteShipment for Chowdeck");
    }

    @Override
    public BigDecimal getWalletBalance() {
        JsonNode data = exchange("/relay/wallet/balance", HttpMethod.GET, null);
        return fromMinorUnits(requiredLong(data, "balance"));
    }

    @Override
    public ShipmentResponse arrangePickup(String shipmentId, String rateId) {
        throw new UnsupportedOperationException("Chowdeck booking requires shipment details");
    }

    @Override
    public boolean requiresShipmentRequestForBooking() {
        return true;
    }

    @Override
    public ShipmentResponse arrangePickup(
            String shipmentId,
            String rateId,
            ShipmentRequest request
    ) {
        ensureAvailable();
        requireRequest(request);
        String reference = requiredText(shipmentId, "Shipment reference is required");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("destination_contact", contact(request.recipientContact()));
        body.put("source_contact", contact(request.senderContact()));
        body.put("fee_id", parseFeeId(rateId));
        body.put("item_type", itemType(request.parcelDetails().type()));
        body.put("user_action", request.userAction() == ShipmentUserAction.RECEIVING
                ? "receiving"
                : "sending");
        body.put("reference", reference);
        body.put("estimated_order_amount", toMinorUnits(request.parcelDetails().value()));
        body.put("customer_delivery_note", bounded(request.parcelDetails().description(), 500));

        try {
            return createdDelivery(reference, exchange("/relay/delivery", HttpMethod.POST, body));
        } catch (ChowdeckRequestException exception) {
            try {
                return reconcileCreatedDelivery(reference);
            } catch (RuntimeException ignored) {
                throw new BusinessException("Chowdeck delivery creation failed");
            }
        } catch (ResourceAccessException exception) {
            try {
                return reconcileCreatedDelivery(reference);
            } catch (RuntimeException ignored) {
                throw new ServiceUnavailableException(REQUEST_FAILED);
            }
        }
    }

    @Override
    public boolean supportsPreBookingTracking() {
        return false;
    }

    @Override
    public boolean draftExistsRemotely() {
        return false;
    }

    @Override
    public ShipmentTrackingStatus trackShipment(String shipmentId) {
        String reference = requiredText(shipmentId, "Shipment reference is required");
        JsonNode data = exchange(
                "/relay/delivery/" + UriUtils.encodePathSegment(reference, StandardCharsets.UTF_8),
                HttpMethod.GET,
                null
        );
        return trackingStatus(reference, data);
    }

    private ShipmentTrackingStatus trackingStatus(String reference, JsonNode data) {
        String returnedReference = requiredText(firstText(data, "reference"), "Invalid Chowdeck response");
        if (!reference.equals(returnedReference)) {
            throw new BusinessException("Chowdeck returned a mismatched delivery");
        }

        List<ShipmentTrackingCheckpoint> history = new ArrayList<>();
        JsonNode timeline = data.get("timeline");
        if (timeline != null && timeline.isArray()) {
            for (JsonNode event : timeline) {
                history.add(new ShipmentTrackingCheckpoint(
                        normalizeStatus(firstText(event, "status", "action")),
                        parseInstant(firstText(event, "created_at", "updated_at")),
                        firstText(event, "description"),
                        null
                ));
            }
        }
        return new ShipmentTrackingStatus(
                reference,
                normalizeStatus(firstText(data, "status", "delivery_status")),
                parseInstant(firstText(data, "updated_at", "created_at")),
                List.copyOf(history),
                reference,
                firstText(data, "tracking_url")
        );
    }

    @Override
    public ShipmentResponse cancelShipment(String shipmentId) {
        return cancelShipment(shipmentId, "ShopSecure shipment cancellation");
    }

    @Override
    public ShipmentResponse cancelShipment(String shipmentId, String reason) {
        String reference = requiredText(shipmentId, "Shipment reference is required");
        JsonNode data;
        try {
            data = exchange(
                    "/relay/delivery/"
                            + UriUtils.encodePathSegment(reference, StandardCharsets.UTF_8)
                            + "/cancel",
                    HttpMethod.POST,
                    Map.of("reason", bounded(requiredText(reason, "Cancellation reason is required"), 500))
            );
        } catch (ChowdeckRequestException | ResourceAccessException exception) {
            ShipmentTrackingStatus tracking;
            try {
                tracking = trackShipment(reference);
            } catch (RuntimeException ignored) {
                throw new ServiceUnavailableException("Chowdeck cancellation could not be confirmed");
            }
            if (tracking.currentStatus() != ShipmentStatus.CANCELLED) {
                throw new ServiceUnavailableException("Chowdeck cancellation could not be confirmed");
            }
            return trackingResponse(tracking);
        }
        String returnedReference = firstText(data, "reference");
        if (returnedReference != null && !reference.equals(returnedReference)) {
            throw new BusinessException("Chowdeck returned a mismatched cancellation");
        }
        return new ShipmentResponse(
                reference,
                ShipmentStatus.CANCELLED,
                reference,
                firstText(data, "tracking_url"),
                parseInstant(firstText(data, "updated_at"))
        );
    }

    @Override
    public ShipmentStatusUpdatedEvent handleWebhookEvent(WebhookPayload payload) {
        throw new UnsupportedOperationException("Chowdeck webhooks are not enabled");
    }

    private ShipmentResponse createdDelivery(String expectedReference, JsonNode data) {
        String reference = requiredText(firstText(data, "reference"), "Invalid Chowdeck response");
        if (!expectedReference.equals(reference)) {
            throw new BusinessException("Chowdeck returned a mismatched delivery");
        }
        JsonNode madePayment = data.get("made_payment");
        if (madePayment == null || !madePayment.isBoolean() || !madePayment.asBoolean()) {
            throw new BusinessException("Chowdeck did not confirm wallet payment");
        }
        ShipmentStatus status = normalizeStatus(firstText(data, "status", "delivery_status"));
        if (status == ShipmentStatus.UNKNOWN) {
            return reconcileCreatedDelivery(reference);
        }
        return new ShipmentResponse(
                reference,
                status,
                reference,
                firstText(data, "tracking_url"),
                parseInstant(firstText(data, "updated_at"))
        );
    }

    private ShipmentResponse trackingResponse(ShipmentTrackingStatus tracking) {
        return new ShipmentResponse(
                tracking.shipmentId(),
                tracking.currentStatus(),
                tracking.trackingReference(),
                tracking.trackingUrl(),
                tracking.lastUpdated()
        );
    }

    private ShipmentResponse reconcileCreatedDelivery(String reference) {
        JsonNode data = exchange(
                "/relay/delivery/" + UriUtils.encodePathSegment(reference, StandardCharsets.UTF_8),
                HttpMethod.GET,
                null
        );
        JsonNode madePayment = data.get("made_payment");
        boolean paymentConfirmed = madePayment != null
                && madePayment.isBoolean()
                && madePayment.asBoolean();
        if (!paymentConfirmed && firstText(data, "time_payment_confirmed") == null) {
            throw new BusinessException("Chowdeck delivery payment was not confirmed");
        }
        return trackingResponse(trackingStatus(reference, data));
    }

    private JsonNode exchange(String path, HttpMethod method, Map<String, Object> body) {
        ensureAvailable();
        try {
            HttpEntity<?> entity = body == null
                    ? new HttpEntity<>(headers())
                    : new HttpEntity<>(body, headers());
            ResponseEntity<String> response = restTemplate.exchange(
                    baseUrl + path,
                    method,
                    entity,
                    String.class
            );
            if (!response.getStatusCode().is2xxSuccessful()
                    || response.getBody() == null
                    || response.getBody().isBlank()) {
                throw new ChowdeckRequestException("Invalid response");
            }
            JsonNode root = readBody(response.getBody());
            JsonNode status = root.get("status");
            boolean successful = status != null
                    && ((status.isBoolean() && status.asBoolean())
                    || "success".equalsIgnoreCase(status.asText()));
            if (!successful) {
                throw new ChowdeckRequestException(valueOrDefault(firstText(root, "message"), "Request failed"));
            }
            JsonNode data = root.get("data");
            return data == null || data.isNull() ? objectMapper.createObjectNode() : data;
        } catch (RestClientResponseException exception) {
            String message = "Request failed";
            try {
                message = valueOrDefault(
                        firstText(readBody(exception.getResponseBodyAsString()), "message"),
                        message
                );
            } catch (RuntimeException ignored) {
                // Keep provider response bodies out of logs and client errors.
            }
            throw new ChowdeckRequestException(message);
        } catch (ResourceAccessException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new ServiceUnavailableException(REQUEST_FAILED);
        }
    }

    private JsonNode readBody(String body) {
        try {
            JsonNode parsed = objectMapper.readTree(body);
            if (parsed == null || !parsed.isObject()) {
                throw new BusinessException("Invalid Chowdeck response");
            }
            return parsed;
        } catch (Exception exception) {
            throw new BusinessException("Invalid Chowdeck response");
        }
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(secretKey);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private Map<String, Object> contact(ShipmentContact contact) {
        if (contact == null) {
            throw new BusinessException("Shipment contact details are required");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", requiredText(
                (valueOrDefault(contact.firstName(), "") + " " + valueOrDefault(contact.lastName(), "")).trim(),
                "Shipment contact name is required"
        ));
        result.put("phone", localPhone(contact.phone()));
        result.put("country_code", "NG");
        if (contact.email() != null && !contact.email().isBlank()) {
            result.put("email", contact.email().trim());
        }
        return result;
    }

    private String addressString(ShipmentAddress address) {
        if (address == null) {
            throw new BusinessException("Shipping address is required");
        }
        List<String> parts = new ArrayList<>();
        addText(parts, address.line1());
        addText(parts, address.line2());
        addText(parts, address.city());
        addText(parts, address.state());
        addText(parts, address.postalCode());
        addText(parts, "NG".equalsIgnoreCase(address.country()) ? "Nigeria" : address.country());
        if (parts.size() < 4) {
            throw new BusinessException("Shipping address is incomplete");
        }
        return String.join(", ", parts);
    }

    private void addText(List<String> values, String value) {
        if (value != null && !value.isBlank()) {
            values.add(value.trim());
        }
    }

    private String localPhone(String phone) {
        String normalized = NigerianPhoneNumber.normalize(phone);
        return "0" + normalized.substring(4);
    }

    private String itemType(String value) {
        String normalized = value == null ? "package" : value.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? "package" : bounded(normalized, 100);
    }

    private long toMinorUnits(BigDecimal amount) {
        if (amount == null || amount.signum() < 0) {
            return 0;
        }
        try {
            long minorUnits = amount.setScale(2, RoundingMode.UNNECESSARY)
                    .movePointRight(2)
                    .longValueExact();
            if (minorUnits > Integer.MAX_VALUE) {
                throw new BusinessException("Shipment value exceeds Chowdeck's supported amount");
            }
            return minorUnits;
        } catch (ArithmeticException exception) {
            throw new BusinessException("Shipment value cannot be represented in kobo");
        }
    }

    private BigDecimal fromMinorUnits(long amount) {
        if (amount < 0) {
            throw new BusinessException("Invalid Chowdeck amount");
        }
        return BigDecimal.valueOf(amount, 2).setScale(2, RoundingMode.UNNECESSARY);
    }

    private long requiredLong(JsonNode source, String field) {
        JsonNode value = source == null ? null : source.get(field);
        if (value == null || !value.isIntegralNumber() || value.longValue() < 0) {
            throw new BusinessException("Invalid Chowdeck response");
        }
        return value.longValue();
    }

    private String requiredIntegralText(JsonNode source, String field) {
        return Long.toString(requiredLong(source, field));
    }

    private int parseFeeId(String rateId) {
        try {
            int value = Integer.parseInt(requiredText(rateId, "Chowdeck fee id is required"));
            if (value <= 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new BusinessException("Chowdeck fee id is invalid");
        }
    }

    private ShipmentStatus normalizeStatus(String value) {
        if (value == null || value.isBlank()) {
            return ShipmentStatus.UNKNOWN;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "preparing", "received", "accepted" -> ShipmentStatus.PENDING;
            case "awaiting_pickup", "assigned" -> ShipmentStatus.CONFIRMED;
            case "picked", "picked_up", "in_transit", "arrived", "arrived_at_customer_location" ->
                    ShipmentStatus.IN_TRANSIT;
            case "success", "completed", "complete", "delivered", "order_complete" ->
                    ShipmentStatus.DELIVERED;
            case "rejected", "cancelled", "canceled" -> ShipmentStatus.CANCELLED;
            case "failed", "failure" -> ShipmentStatus.FAILED;
            case "draft" -> ShipmentStatus.DRAFT;
            default -> ShipmentStatus.UNKNOWN;
        };
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (Exception exception) {
            return null;
        }
    }

    private String firstText(JsonNode source, String... fields) {
        if (source == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = source.get(field);
            if (value != null && !value.isNull() && value.isValueNode()) {
                String text = value.asText();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    private String requiredText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(message);
        }
        return value.trim();
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String bounded(String value, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private void requireRequest(ShipmentRequest request) {
        if (request == null || request.parcelDetails() == null) {
            throw new BusinessException("Shipment details are required");
        }
        requiredText(request.orderReference(), "Shipment reference is required");
        addressString(request.pickupAddress());
        addressString(request.deliveryAddress());
    }

    private void ensureAvailable() {
        if (hardDisabled) {
            throw new ServiceUnavailableException("Chowdeck shipping is disabled");
        }
        if (secretKey.isBlank() || baseUrl.isBlank()) {
            throw new ServiceUnavailableException("Chowdeck shipping is not configured");
        }
    }

    private static final class ChowdeckRequestException extends ServiceUnavailableException {
        private ChowdeckRequestException(String message) {
            super(message);
        }

        private String message() {
            return getMessage() == null ? "Request failed" : getMessage();
        }
    }
}
