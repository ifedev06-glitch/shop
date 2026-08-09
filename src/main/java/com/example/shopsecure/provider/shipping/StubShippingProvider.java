package com.example.shopsecure.provider.shipping;

import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.provider.common.WebhookPayload;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class StubShippingProvider implements ShippingProvider {

    private static final String STANDARD_RATE_ID = "stub-standard";
    private static final String EXPRESS_RATE_ID = "stub-express";

    private final ObjectMapper objectMapper;
    private final String webhookSecret;
    private final ConcurrentMap<String, ShipmentRequest> shipmentRequests = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ShipmentTrackingStatus> shipments = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ShipmentResponse> shipmentResponses = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> rateShipments = new ConcurrentHashMap<>();

    public StubShippingProvider(ObjectMapper objectMapper, String webhookSecret) {
        this.objectMapper = objectMapper;
        this.webhookSecret = webhookSecret;
    }

    @Override
    public AddressValidationResult validateAddress(ShipmentAddress address) {
        boolean valid = address != null
                && hasText(address.line1())
                && hasText(address.city())
                && hasText(address.state())
                && hasText(address.country());
        return new AddressValidationResult(valid, valid ? address : null);
    }

    @Override
    public ShipmentResponse createQuickShipment(ShipmentRequest request) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        String shipmentId = "SHP-MOCK-" + suffix;
        String trackingReference = "TRK-MOCK-" + suffix;
        Instant now = Instant.now();
        ShipmentTrackingStatus trackingStatus = new ShipmentTrackingStatus(
                ShipmentStatus.DRAFT,
                now,
                List.of(new ShipmentTrackingCheckpoint(
                        ShipmentStatus.DRAFT,
                        now,
                        "Draft shipment created with mock carrier",
                        request.pickupAddress() == null ? null : request.pickupAddress().city()
                ))
        );
        ShipmentResponse response = new ShipmentResponse(
                shipmentId,
                ShipmentStatus.DRAFT,
                trackingReference,
                "https://tracking.example.test/" + trackingReference,
                now
        );
        shipmentRequests.put(shipmentId, request);
        shipments.put(shipmentId, trackingStatus);
        shipmentResponses.put(shipmentId, response);
        return response;
    }

    @Override
    public List<ShippingRateOption> getRates(String shipmentId, String currency) {
        ShipmentRequest request = shipmentRequests.get(shipmentId);
        if (request == null) {
            throw new ResourceNotFoundException("Shipment not found");
        }
        BigDecimal weight = request.parcelDetails() == null
                || request.parcelDetails().weightKg() == null
                ? BigDecimal.ONE
                : request.parcelDetails().weightKg().max(BigDecimal.ONE);
        BigDecimal standardCost = new BigDecimal("4250.00")
                .add(weight.multiply(new BigDecimal("250.00")))
                .setScale(2, RoundingMode.HALF_UP);
        String rateCurrency = hasText(currency) ? currency.toUpperCase(Locale.ROOT) : "NGN";
        String standardRateId = shipmentId + ":" + STANDARD_RATE_ID;
        String expressRateId = shipmentId + ":" + EXPRESS_RATE_ID;
        rateShipments.put(standardRateId, shipmentId);
        rateShipments.put(expressRateId, shipmentId);
        return List.of(
                new ShippingRateOption(
                        standardRateId,
                        shipmentId + ":standard",
                        "Mock Logistics",
                        null,
                        "Standard delivery",
                        standardCost,
                        rateCurrency,
                        "Within 24 hours",
                        "3-5 business days"
                ),
                new ShippingRateOption(
                        expressRateId,
                        shipmentId + ":express",
                        "Mock Logistics",
                        null,
                        "Express delivery",
                        standardCost.add(new BigDecimal("2500.00")),
                        rateCurrency,
                        "Within 12 hours",
                        "1-2 business days"
                )
        );
    }

    @Override
    public BigDecimal getWalletBalance() {
        return new BigDecimal("1000000000.00");
    }

    @Override
    public ShipmentResponse arrangePickup(String shipmentId, String rateId) {
        requireShipment(shipmentId);
        if (!shipmentId.equals(rateShipments.get(rateId))) {
            throw new BusinessException("Selected shipping rate is not available for this shipment");
        }
        return updateShipment(shipmentId, ShipmentStatus.CONFIRMED, "Pickup arranged with mock carrier");
    }

    @Override
    public ShipmentTrackingStatus trackShipment(String shipmentId) {
        return requireShipment(shipmentId);
    }

    @Override
    public ShipmentResponse cancelShipment(String shipmentId) {
        requireShipment(shipmentId);
        return updateShipment(shipmentId, ShipmentStatus.CANCELLED, "Shipment cancelled");
    }

    @Override
    public ShipmentStatusUpdatedEvent handleWebhookEvent(WebhookPayload payload) {
        if (!validWebhookSignature(payload.rawBody(), payload.header("x-shipping-signature"))) {
            throw new BusinessException("Invalid shipping webhook signature");
        }
        try {
            JsonNode root = objectMapper.readTree(payload.rawBody());
            if (!"shipment.status.updated".equals(root.path("eventType").asText())) {
                return ShipmentStatusUpdatedEvent.ignored();
            }
            String shipmentId = root.path("shipmentId").asText();
            ShipmentStatus status = normalizeStatus(root.path("status").asText());
            Instant occurredAt = root.path("occurredAt").isMissingNode()
                    ? Instant.now()
                    : Instant.parse(root.path("occurredAt").asText());
            if (shipments.containsKey(shipmentId)) {
                updateShipment(shipmentId, status, "Mock carrier status update", occurredAt);
            }
            return new ShipmentStatusUpdatedEvent(shipmentId, status, occurredAt, true);
        } catch (Exception exception) {
            throw new BusinessException("Invalid shipping webhook payload");
        }
    }

    private ShipmentTrackingStatus requireShipment(String shipmentId) {
        ShipmentTrackingStatus status = shipments.get(shipmentId);
        if (status == null) {
            throw new ResourceNotFoundException("Shipment not found");
        }
        return status;
    }

    private ShipmentResponse updateShipment(String shipmentId, ShipmentStatus status, String description) {
        return updateShipment(shipmentId, status, description, Instant.now());
    }

    private ShipmentResponse updateShipment(
            String shipmentId,
            ShipmentStatus status,
            String description,
            Instant occurredAt
    ) {
        ShipmentTrackingStatus current = requireShipment(shipmentId);
        List<ShipmentTrackingCheckpoint> history = new ArrayList<>(current.history());
        history.add(new ShipmentTrackingCheckpoint(status, occurredAt, description, null));
        shipments.put(shipmentId, new ShipmentTrackingStatus(status, occurredAt, history));

        ShipmentResponse currentResponse = shipmentResponses.get(shipmentId);
        ShipmentResponse updated = new ShipmentResponse(
                shipmentId,
                status,
                currentResponse.trackingReference(),
                currentResponse.trackingUrl(),
                occurredAt
        );
        shipmentResponses.put(shipmentId, updated);
        return updated;
    }

    private ShipmentStatus normalizeStatus(String value) {
        if (value == null) {
            return ShipmentStatus.UNKNOWN;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT).replace('_', '-')) {
            case "draft", "created" -> ShipmentStatus.DRAFT;
            case "confirmed", "booked" -> ShipmentStatus.CONFIRMED;
            case "pending", "processing" -> ShipmentStatus.PENDING;
            case "in-transit", "in transit", "picked-up", "picked up" -> ShipmentStatus.IN_TRANSIT;
            case "delivered" -> ShipmentStatus.DELIVERED;
            case "cancelled", "canceled" -> ShipmentStatus.CANCELLED;
            case "failed", "failure" -> ShipmentStatus.FAILED;
            default -> ShipmentStatus.UNKNOWN;
        };
    }

    private boolean validWebhookSignature(String body, String signature) {
        if (!hasText(webhookSecret) || body == null || signature == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.US_ASCII),
                    signature.getBytes(StandardCharsets.US_ASCII)
            );
        } catch (Exception exception) {
            return false;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
