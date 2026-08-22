package com.example.shopsecure.service;

import com.example.shopsecure.provider.common.WebhookPayload;
import com.example.shopsecure.provider.shipping.ShipmentStatusUpdatedEvent;
import com.example.shopsecure.provider.shipping.ShippingProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class ShippingWebhookRouter {

    private final ShippingProvider shippingProvider;
    private final WaybillShippingService waybillShippingService;
    private final OrderShippingService orderShippingService;

    public void handleWebhook(WebhookPayload payload) {
        ShipmentStatusUpdatedEvent event = shippingProvider.handleWebhookEvent(payload);
        if (!event.relevant()) {
            return;
        }
        String eventKey = event.shipmentId() + ":" + event.status() + ":" + payloadHash(payload.rawBody());
        if (waybillShippingService.ownsShipment(event.shipmentId())) {
            waybillShippingService.applyWebhookEvent(event, eventKey);
            return;
        }
        orderShippingService.applyWebhookEvent(event, eventKey);
    }

    private String payloadHash(String body) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(body.getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to fingerprint webhook", exception);
        }
    }
}
