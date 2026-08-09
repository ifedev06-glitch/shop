package com.example.shopsecure.provider.shipping;

import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.provider.common.WebhookPayload;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class TerminalAfricaShippingProvider implements ShippingProvider {

    private static final String INVALID_RESPONSE = "Terminal Africa returned an invalid response";
    private static final String REQUEST_FAILED = "Terminal Africa request failed";
    private static final Set<String> SHIPMENT_WEBHOOK_EVENTS = Set.of(
            "shipment.updated",
            "shipment.in-transit",
            "shipment.delivered",
            "shipment.cancelled",
            "shipment.created"
    );
    private static final List<String> NESTED_CONTAINERS = List.of(
            "shipment",
            "data",
            "resource",
            "payload",
            "tracking_status",
            "tracking",
            "extras",
            "event"
    );

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String secretKey;
    private final String baseUrl;

    public TerminalAfricaShippingProvider(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            String secretKey,
            String baseUrl
    ) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.secretKey = secretKey;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    }

    @Override
    public AddressValidationResult validateAddress(ShipmentAddress address) {
        if (address == null) {
            throw new BusinessException("Shipping address is required");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("city", address.city());
        body.put("country", address.country());
        body.put("line1", address.line1());
        putIfNotNull(body, "line2", address.line2());
        body.put("state", address.state());
        body.put("zip", address.postalCode());

        JsonNode data = post("/addresses/validate", body);
        JsonNode validNode = data.get("is_valid");
        if (validNode == null || !validNode.isBoolean()) {
            throw invalidResponse();
        }
        JsonNode suggestedNode = data.get("suggested_address");
        ShipmentAddress suggestedAddress = suggestedNode != null && suggestedNode.isObject()
                ? toAddress(suggestedNode, address)
                : null;
        return new AddressValidationResult(validNode.asBoolean(), suggestedAddress);
    }

    @Override
    public ShipmentResponse createQuickShipment(ShipmentRequest request) {
        if (request == null || request.parcelDetails() == null) {
            throw new BusinessException("Shipment details are required");
        }

        ParcelDetails parcelDetails = request.parcelDetails();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", parcelDetails.itemName());
        item.put("description", parcelDetails.description());
        item.put("value", parcelDetails.value());
        item.put("currency", parcelDetails.currency());
        item.put("quantity", parcelDetails.quantity());
        item.put("weight", parcelDetails.weightKg());
        item.put("type", parcelDetails.type());

        Map<String, Object> parcel = new LinkedHashMap<>();
        parcel.put("description", parcelDetails.description());
        parcel.put("weight_unit", "kg");
        parcel.put("items", List.of(item));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("order_reference", request.orderReference());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pickup_address", toProviderAddress(request.pickupAddress(), request.senderContact()));
        body.put("delivery_address", toProviderAddress(request.deliveryAddress(), request.recipientContact()));
        body.put("metadata", metadata);
        body.put("shipment_purpose", "commercial");
        body.put("parcel", parcel);

        return toShipmentResponse(post("/shipments/quick", body));
    }

    @Override
    public List<ShippingRateOption> getRates(String shipmentId, String currency) {
        requireText(shipmentId, "Shipment id is required");
        requireText(currency, "Shipping currency is required");
        String path = "/rates/shipment?shipment_id="
                + UriUtils.encodeQueryParam(shipmentId, StandardCharsets.UTF_8)
                + "&currency="
                + UriUtils.encodeQueryParam(currency, StandardCharsets.UTF_8);
        JsonNode data = get(path);
        JsonNode rates = data.isArray() ? data : data.get("rates");
        if (rates == null || !rates.isArray()) {
            throw invalidResponse();
        }

        List<ShippingRateOption> result = new ArrayList<>();
        for (JsonNode rate : rates) {
            if (!rate.isObject()) {
                throw invalidResponse();
            }
            if (booleanValue(rate, "dropoff_required", "drop_off_required")) {
                continue;
            }
            result.add(new ShippingRateOption(
                    requiredNodeText(rate, "rate_id"),
                    requiredNodeText(rate, "id"),
                    firstNestedText(rate, "carrier_name"),
                    firstNestedText(rate, "carrier_logo"),
                    firstNestedText(rate, "carrier_rate_description", "service_description"),
                    requiredDecimal(rate, "amount"),
                    valueOrDefault(firstNestedText(rate, "currency"), currency),
                    firstNestedText(rate, "pickup_time", "pickup_eta"),
                    firstNestedText(rate, "delivery_time", "delivery_date", "delivery_eta")
            ));
        }
        return List.copyOf(result);
    }

    private boolean booleanValue(JsonNode source, String... fields) {
        for (String field : fields) {
            JsonNode value = source.get(field);
            if (value != null && value.isBoolean()) {
                return value.asBoolean();
            }
        }
        return false;
    }

    @Override
    public BigDecimal getWalletBalance() {
        JsonNode data = get("/users/wallet-balance");
        JsonNode amount = data.get("amount");
        if (amount == null || !amount.isNumber() || amount.decimalValue().signum() < 0) {
            throw invalidResponse();
        }
        return amount.decimalValue();
    }

    @Override
    public ShipmentResponse arrangePickup(String shipmentId, String rateId) {
        requireText(shipmentId, "Shipment id is required");
        requireText(rateId, "Shipping rate id is required");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("shipment_id", shipmentId);
        body.put("rate_id", rateId);
        return toShipmentResponse(post("/shipments/pickup", body));
    }

    @Override
    public ShipmentTrackingStatus trackShipment(String shipmentId) {
        requireText(shipmentId, "Shipment id is required");
        String encodedShipmentId = UriUtils.encodePathSegment(shipmentId, StandardCharsets.UTF_8);
        return toTrackingStatus(get("/shipments/track/" + encodedShipmentId));
    }

    @Override
    public ShipmentResponse cancelShipment(String shipmentId) {
        requireText(shipmentId, "Shipment id is required");
        return toShipmentResponse(post("/shipments/cancel", Map.of("shipment_id", shipmentId)));
    }

    @Override
    public ShipmentStatusUpdatedEvent handleWebhookEvent(WebhookPayload payload) {
        if (payload == null || !validSignature(payload.rawBody(), payload.header("X-Terminal-Signature"))) {
            throw new BusinessException("Invalid Terminal Africa webhook signature");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(payload.rawBody());
        } catch (Exception exception) {
            throw new BusinessException("Invalid Terminal Africa webhook payload");
        }
        if (root == null || !root.isObject()) {
            throw new BusinessException("Invalid Terminal Africa webhook payload");
        }

        String eventName = firstDirectText(root, "event", "event_type", "eventType");
        if (eventName == null) {
            throw new BusinessException("Invalid Terminal Africa webhook payload");
        }
        eventName = eventName.trim().toLowerCase(Locale.ROOT).replace("_", "-");
        if (!SHIPMENT_WEBHOOK_EVENTS.contains(eventName)) {
            return ShipmentStatusUpdatedEvent.ignored();
        }

        JsonNode data = root.get("data");
        if (data == null || data.isNull() || (!data.isObject() && !data.isArray())) {
            throw new BusinessException("Invalid Terminal Africa webhook payload");
        }
        String shipmentId = firstNestedText(data, "shipment_id", "shipmentId");
        if (shipmentId == null) {
            shipmentId = firstDirectText(root, "shipment_id", "shipmentId");
        }
        if (shipmentId == null) {
            throw new BusinessException("Invalid Terminal Africa webhook payload");
        }

        JsonNode events = findEvents(data, 0);
        String statusValue = firstNestedText(data, "status", "shipment_status", "current_status");
        if (statusValue == null) {
            statusValue = latestEventText(events, "status", "shipment_status", "current_status");
        }
        ShipmentStatus eventStatus = statusForEvent(eventName);
        ShipmentStatus status = eventStatus == ShipmentStatus.UNKNOWN
                ? normalizeStatus(statusValue)
                : eventStatus;

        String occurredAtValue = firstDirectText(root, "occurred_at", "occurredAt", "timestamp");
        if (occurredAtValue == null) {
            occurredAtValue = firstNestedText(data, "updated_at", "updatedAt", "occurred_at", "occurredAt");
        }
        if (occurredAtValue == null) {
            occurredAtValue = latestEventText(events, "created_at", "createdAt", "timestamp", "updated_at");
        }
        Instant occurredAt = parseInstant(occurredAtValue);
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
        return new ShipmentStatusUpdatedEvent(shipmentId, status, occurredAt, true);
    }

    private Map<String, Object> toProviderAddress(ShipmentAddress address, ShipmentContact contact) {
        if (address == null || contact == null) {
            throw new BusinessException("Shipment address and contact details are required");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("first_name", contact.firstName());
        result.put("last_name", contact.lastName());
        result.put("email", contact.email());
        result.put("phone", contact.phone());
        result.put("line1", address.line1());
        putIfNotNull(result, "line2", address.line2());
        result.put("city", address.city());
        result.put("state", address.state());
        result.put("country", address.country());
        putIfNotNull(result, "zip", address.postalCode());
        return result;
    }

    private ShipmentAddress toAddress(JsonNode source, ShipmentAddress fallback) {
        return new ShipmentAddress(
                valueOrDefault(firstDirectText(source, "line1"), fallback.line1()),
                valueOrDefault(firstDirectText(source, "line2"), fallback.line2()),
                valueOrDefault(firstDirectText(source, "city"), fallback.city()),
                valueOrDefault(firstDirectText(source, "state"), fallback.state()),
                valueOrDefault(firstDirectText(source, "country"), fallback.country()),
                valueOrDefault(firstDirectText(source, "zip", "postal_code", "postalCode"), fallback.postalCode())
        );
    }

    private ShipmentResponse toShipmentResponse(JsonNode data) {
        String shipmentId = firstNestedText(data, "shipment_id", "shipmentId");
        String statusValue = firstNestedText(data, "status", "shipment_status");
        if (shipmentId == null || statusValue == null) {
            throw invalidResponse();
        }
        return new ShipmentResponse(
                shipmentId,
                normalizeStatus(statusValue),
                firstNestedText(data, "tracking_number", "carrier_tracking_number", "tracking_reference"),
                firstNestedText(data, "tracking_url", "carrier_tracking_url"),
                parseInstant(firstNestedText(data, "updated_at", "updatedAt"))
        );
    }

    private ShipmentTrackingStatus toTrackingStatus(JsonNode data) {
        if (firstNestedText(data, "shipment_id", "shipmentId") == null) {
            throw invalidResponse();
        }
        JsonNode events = findEvents(data, 0);
        List<ShipmentTrackingCheckpoint> history = new ArrayList<>();
        if (events != null) {
            for (JsonNode event : events) {
                if (!event.isObject()) {
                    continue;
                }
                history.add(new ShipmentTrackingCheckpoint(
                        normalizeStatus(firstNestedText(event, "status", "shipment_status", "current_status")),
                        parseInstant(firstNestedText(event, "created_at", "createdAt", "timestamp", "updated_at")),
                        firstNestedText(event, "description", "message"),
                        firstNestedText(event, "location")
                ));
            }
        }

        String statusValue = firstNestedText(data, "status", "shipment_status", "current_status");
        if (statusValue == null && events != null) {
            statusValue = latestEventText(events, "status", "shipment_status", "current_status");
        }
        Instant lastUpdated = parseInstant(firstNestedText(
                data,
                "updated_at",
                "updatedAt",
                "last_updated",
                "lastUpdated"
        ));
        if (lastUpdated == null) {
            for (ShipmentTrackingCheckpoint checkpoint : history) {
                if (checkpoint.timestamp() != null
                        && (lastUpdated == null || checkpoint.timestamp().isAfter(lastUpdated))) {
                    lastUpdated = checkpoint.timestamp();
                }
            }
        }
        return new ShipmentTrackingStatus(normalizeStatus(statusValue), lastUpdated, history);
    }

    private JsonNode get(String path) {
        return exchange(path, HttpMethod.GET, null);
    }

    private JsonNode post(String path, Map<String, Object> body) {
        return exchange(path, HttpMethod.POST, body);
    }

    private JsonNode exchange(String path, HttpMethod method, Map<String, Object> body) {
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
                throw invalidResponse();
            }

            JsonNode root;
            try {
                root = objectMapper.readTree(response.getBody());
            } catch (Exception exception) {
                throw invalidResponse();
            }
            if (root == null || !root.isObject()) {
                throw invalidResponse();
            }
            JsonNode success = root.get("status");
            JsonNode data = root.get("data");
            if (success == null || !success.isBoolean() || !success.asBoolean()
                    || data == null || data.isNull()) {
                throw invalidResponse();
            }
            return data;
        } catch (RestClientResponseException | ResourceAccessException exception) {
            throw new BusinessException(REQUEST_FAILED);
        } catch (RestClientException exception) {
            throw new BusinessException(REQUEST_FAILED);
        }
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(secretKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    private String firstNestedText(JsonNode source, String... keys) {
        return firstNestedText(source, 0, keys);
    }

    private String firstNestedText(JsonNode source, int depth, String... keys) {
        if (source == null || source.isNull() || depth > 6) {
            return null;
        }
        String direct = firstDirectText(source, keys);
        if (direct != null) {
            return direct;
        }
        if (source.isArray()) {
            for (JsonNode child : source) {
                String value = firstNestedText(child, depth + 1, keys);
                if (value != null) {
                    return value;
                }
            }
            return null;
        }
        for (String container : NESTED_CONTAINERS) {
            JsonNode child = source.get(container);
            if (child != null && (child.isObject() || child.isArray())) {
                String value = firstNestedText(child, depth + 1, keys);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private String firstDirectText(JsonNode source, String... keys) {
        if (source == null || !source.isObject()) {
            return null;
        }
        for (String key : keys) {
            JsonNode value = source.get(key);
            if (value != null && !value.isNull() && value.isValueNode()) {
                String text = value.asText();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    private JsonNode findEvents(JsonNode source, int depth) {
        if (source == null || source.isNull() || depth > 6) {
            return null;
        }
        if (source.isObject()) {
            JsonNode events = source.get("events");
            if (events != null && events.isArray()) {
                return events;
            }
            for (String container : NESTED_CONTAINERS) {
                JsonNode child = source.get(container);
                if (child != null && (child.isObject() || child.isArray())) {
                    JsonNode nestedEvents = findEvents(child, depth + 1);
                    if (nestedEvents != null) {
                        return nestedEvents;
                    }
                }
            }
        } else if (source.isArray()) {
            for (JsonNode child : source) {
                JsonNode nestedEvents = findEvents(child, depth + 1);
                if (nestedEvents != null) {
                    return nestedEvents;
                }
            }
        }
        return null;
    }

    private String latestEventText(JsonNode events, String... keys) {
        if (events == null || !events.isArray()) {
            return null;
        }
        String result = null;
        for (JsonNode event : events) {
            String value = firstNestedText(event, keys);
            if (value != null) {
                result = value;
            }
        }
        return result;
    }

    private String requiredNodeText(JsonNode source, String key) {
        String value = firstDirectText(source, key);
        if (value == null) {
            throw invalidResponse();
        }
        return value;
    }

    private BigDecimal requiredDecimal(JsonNode source, String key) {
        JsonNode value = source.get(key);
        if (value == null || value.isNull() || (!value.isNumber() && !value.isTextual())) {
            throw invalidResponse();
        }
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException exception) {
            throw invalidResponse();
        }
    }

    private ShipmentStatus normalizeStatus(String value) {
        if (value == null) {
            return ShipmentStatus.UNKNOWN;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (normalized) {
            case "draft", "created" -> ShipmentStatus.DRAFT;
            case "confirmed", "booked" -> ShipmentStatus.CONFIRMED;
            case "pending", "processing", "queued" -> ShipmentStatus.PENDING;
            case "in-transit", "in transit", "transit", "picked-up", "picked up", "out-for-delivery" ->
                    ShipmentStatus.IN_TRANSIT;
            case "delivered", "completed" -> ShipmentStatus.DELIVERED;
            case "cancelled", "canceled", "cancel" -> ShipmentStatus.CANCELLED;
            case "failed", "failure", "delivery-failed" -> ShipmentStatus.FAILED;
            default -> ShipmentStatus.UNKNOWN;
        };
    }

    private ShipmentStatus statusForEvent(String eventName) {
        return switch (eventName) {
            case "shipment.created" -> ShipmentStatus.DRAFT;
            case "shipment.in-transit" -> ShipmentStatus.IN_TRANSIT;
            case "shipment.delivered" -> ShipmentStatus.DELIVERED;
            case "shipment.cancelled" -> ShipmentStatus.CANCELLED;
            default -> ShipmentStatus.UNKNOWN;
        };
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception exception) {
            return null;
        }
    }

    private boolean validSignature(String body, String signature) {
        if (body == null || signature == null || secretKey == null || secretKey.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] expected = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            byte[] supplied = HexFormat.of().parseHex(signature.trim());
            return MessageDigest.isEqual(expected, supplied);
        } catch (Exception exception) {
            return false;
        }
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(message);
        }
    }

    private void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private BusinessException invalidResponse() {
        return new BusinessException(INVALID_RESPONSE);
    }
}
