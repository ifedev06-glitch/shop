package com.example.shopsecure.service;

import com.example.shopsecure.provider.common.WebhookPayload;
import com.example.shopsecure.provider.shipping.ShipmentStatus;
import com.example.shopsecure.provider.shipping.ShipmentStatusUpdatedEvent;
import com.example.shopsecure.provider.shipping.ShippingProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShippingWebhookRouterTest {

    @Test
    void preservesStructuredOrderWebhookDedupeKey() throws Exception {
        ShippingProvider provider = mock(ShippingProvider.class);
        WaybillShippingService waybillShippingService = mock(WaybillShippingService.class);
        OrderShippingService orderShippingService = mock(OrderShippingService.class);
        ShippingWebhookRouter router = new ShippingWebhookRouter(
                provider,
                waybillShippingService,
                orderShippingService
        );
        String body = "{\"event\":\"shipment.in-transit\"}";
        ShipmentStatusUpdatedEvent event = new ShipmentStatusUpdatedEvent(
                "SH-123",
                ShipmentStatus.IN_TRANSIT,
                Instant.parse("2026-08-15T10:00:00Z"),
                true
        );
        WebhookPayload payload = new WebhookPayload(body, Map.of());
        when(provider.handleWebhookEvent(payload)).thenReturn(event);
        when(waybillShippingService.ownsShipment("SH-123")).thenReturn(false);

        router.handleWebhook(payload);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(orderShippingService).applyWebhookEvent(org.mockito.ArgumentMatchers.eq(event), key.capture());
        assertThat(key.getValue()).isEqualTo(
                "SH-123:IN_TRANSIT:" + HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))
                )
        );
        verify(waybillShippingService, never()).applyWebhookEvent(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString()
        );
    }
}
