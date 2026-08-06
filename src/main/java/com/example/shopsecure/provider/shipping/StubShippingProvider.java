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
import java.time.LocalDate;
import java.util.List;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class StubShippingProvider implements ShippingProvider {

    private static final String STANDARD_RATE_ID = "stub-standard";
    private static final String EXPRESS_RATE_ID = "stub-express";

    private final ObjectMapper objectMapper;
    private final String webhookSecret;
    private final ConcurrentMap<String, ShipmentTrackingStatus> shipments = new ConcurrentHashMap<>();

    public StubShippingProvider(ObjectMapper objectMapper, String webhookSecret) {
        this.objectMapper = objectMapper;
        this.webhookSecret = webhookSecret;
    }

    @Override
    public List<ShippingRateOption> getRates(ShipmentRequest request) {
        BigDecimal weight = request.parcelDetails() == null
                || request.parcelDetails().weightKg() == null
                ? BigDecimal.ONE
                : request.parcelDetails().weightKg().max(BigDecimal.ONE);
        BigDecimal standardCost = new BigDecimal("4250.00")
                .add(weight.multiply(new BigDecimal("250.00")))
                .setScale(2, RoundingMode.HALF_UP);
        LocalDate today = LocalDate.now();
        return List.of(
                new ShippingRateOption(
                        "Mock Logistics Standard",
                        standardCost,
                        today.plusDays(3),
                        today.plusDays(5),
                        STANDARD_RATE_ID
                ),
                new ShippingRateOption(
                        "Mock Logistics Express",
                        standardCost.add(new BigDecimal("2500.00")),
                        today.plusDays(1),
                        today.plusDays(2),
                        EXPRESS_RATE_ID
                )
        );
    }

    @Override
    public ShipmentResponse createShipment(ShipmentRequest request, String selectedRateId) {
        if (!STANDARD_RATE_ID.equals(selectedRateId) && !EXPRESS_RATE_ID.equals(selectedRateId)) {
            throw new BusinessException("Selected shipping rate is not available");
        }
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        String shipmentId = "SHP-MOCK-" + suffix;
        String trackingReference = "TRK-MOCK-" + suffix;
        Instant now = Instant.now();
        shipments.put(shipmentId, new ShipmentTrackingStatus(
                ShipmentStatus.CREATED,
                now,
                List.of(new ShipmentTrackingCheckpoint(
                        ShipmentStatus.CREATED,
                        now,
                        "Shipment booked with mock carrier",
                        request.pickupAddress() == null ? null : request.pickupAddress().city()
                ))
        ));
        return new ShipmentResponse(shipmentId, ShipmentStatus.CREATED, trackingReference);
    }

    @Override
    public ShipmentTrackingStatus trackShipment(String shipmentId) {
        ShipmentTrackingStatus status = shipments.get(shipmentId);
        if (status == null) {
            throw new ResourceNotFoundException("Shipment not found");
        }
        return status;
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
            ShipmentStatus status = ShipmentStatus.valueOf(root.path("status").asText().toUpperCase(Locale.ROOT));
            Instant occurredAt = root.path("occurredAt").isMissingNode()
                    ? Instant.now()
                    : Instant.parse(root.path("occurredAt").asText());
            ShipmentTrackingStatus current = shipments.get(shipmentId);
            if (current != null) {
                List<ShipmentTrackingCheckpoint> history = new java.util.ArrayList<>(current.history());
                history.add(new ShipmentTrackingCheckpoint(status, occurredAt, "Mock carrier status update", null));
                shipments.put(shipmentId, new ShipmentTrackingStatus(status, occurredAt, history));
            }
            return new ShipmentStatusUpdatedEvent(shipmentId, status, occurredAt, true);
        } catch (Exception exception) {
            throw new BusinessException("Invalid shipping webhook payload");
        }
    }

    private boolean validWebhookSignature(String body, String signature) {
        if (webhookSecret == null || webhookSecret.isBlank() || body == null || signature == null) {
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
}
