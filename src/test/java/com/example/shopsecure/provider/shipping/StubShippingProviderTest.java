package com.example.shopsecure.provider.shipping;

import com.example.shopsecure.provider.common.WebhookPayload;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StubShippingProviderTest {

    private static final String WEBHOOK_SECRET = "test-shipping-webhook-secret";

    private final StubShippingProvider provider = new StubShippingProvider(
            JsonMapper.builder().build(),
            WEBHOOK_SECRET
    );

    @Test
    void returnsRatesCreatesAndTracksShipment() {
        ShipmentRequest request = request();

        var rates = provider.getRates(request);
        assertThat(rates).hasSize(2);
        assertThat(rates.get(0).carrierName()).isEqualTo("Mock Logistics Standard");
        assertThat(rates.get(0).cost()).isPositive();

        ShipmentResponse shipment = provider.createShipment(request, rates.get(0).rateId());
        assertThat(shipment.shipmentId()).startsWith("SHP-MOCK-");
        assertThat(shipment.trackingReference()).startsWith("TRK-MOCK-");
        assertThat(provider.trackShipment(shipment.shipmentId()).currentStatus())
                .isEqualTo(ShipmentStatus.CREATED);

        String body = """
                {"eventType":"shipment.status.updated","shipmentId":"%s","status":"DELIVERED"}
                """.formatted(shipment.shipmentId());
        ShipmentStatusUpdatedEvent event = provider.handleWebhookEvent(new WebhookPayload(
                body,
                Map.of("x-shipping-signature", sign(body))
        ));

        assertThat(event.relevant()).isTrue();
        assertThat(event.status()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(provider.trackShipment(shipment.shipmentId()).currentStatus())
                .isEqualTo(ShipmentStatus.DELIVERED);
    }

    @Test
    void rejectsUnsignedWebhook() {
        assertThatThrownBy(() -> provider.handleWebhookEvent(new WebhookPayload("{}", Map.of())))
                .hasMessage("Invalid shipping webhook signature");
    }

    @Test
    void rejectsPayloadModifiedAfterSigning() {
        String signedBody = "{\"eventType\":\"ignored\"}";
        WebhookPayload modified = new WebhookPayload(
                "{\"eventType\":\"shipment.status.updated\"}",
                Map.of("x-shipping-signature", sign(signedBody))
        );

        assertThatThrownBy(() -> provider.handleWebhookEvent(modified))
                .hasMessage("Invalid shipping webhook signature");
    }

    private ShipmentRequest request() {
        return new ShipmentRequest(
                new ShipmentAddress("1 Vendor Street", null, "Lagos", "Lagos", "Nigeria", null),
                new ShipmentAddress("2 Buyer Road", null, "Abuja", "FCT", "Nigeria", null),
                new ParcelDetails(
                        new BigDecimal("2.5"),
                        new BigDecimal("40"),
                        new BigDecimal("30"),
                        new BigDecimal("20"),
                        "Laptop"
                ),
                new ShipmentContact("Vendor", "vendor@example.com", "2348011111111"),
                new ShipmentContact("Buyer", "buyer@example.com", "2348022222222")
        );
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
