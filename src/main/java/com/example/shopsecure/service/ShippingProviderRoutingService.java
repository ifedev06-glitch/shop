package com.example.shopsecure.service;

import com.example.shopsecure.provider.shipping.ShipmentAddress;
import com.example.shopsecure.provider.shipping.ShipmentRequest;
import com.example.shopsecure.provider.shipping.ShippingProvider;
import com.example.shopsecure.provider.shipping.ShippingProviderCode;
import com.example.shopsecure.provider.shipping.ShippingProviderRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class ShippingProviderRoutingService {

    private static final Set<String> STATE_WIDE_ZONES = Set.of("LAGOS", "OGUN", "DELTA", "FCT");

    private final ShippingProviderRegistry registry;
    private final ChowdeckControlService controlService;

    public PreparedShipment prepare(ShipmentRequest request, String currency) {
        ChowdeckControlService.Decision decision = controlService.decision();
        if (!eligibleForChowdeck(request)) {
            return prepareWithDefault(request, currency);
        }
        if (decision.shadowEnabled()) {
            shadowQuote(request, currency);
            return prepareWithDefault(request, currency);
        }
        if (!decision.enabledForNewShipments()) {
            return prepareWithDefault(request, currency);
        }

        try {
            return new PreparedShipment(
                    ShippingProviderCode.CHOWDECK,
                    registry.chowdeckProvider().prepareShipment(request, currency)
            );
        } catch (RuntimeException exception) {
            log.warn("Chowdeck quote unavailable; using the default provider: reference={}",
                    request.orderReference());
            return prepareWithDefault(request, currency);
        }
    }

    public ShippingProvider require(String providerCode) {
        return registry.require(providerCode);
    }

    public ShippingProvider.ShippingQuoteResult quote(
            String providerCode,
            String shipmentId,
            ShipmentRequest request,
            String currency
    ) {
        ShippingProvider provider = registry.require(providerCode);
        if (ShippingProviderCode.CHOWDECK.equalsIgnoreCase(providerCode)) {
            return provider.quoteShipment(shipmentId, request, currency);
        }
        return new ShippingProvider.ShippingQuoteResult(
                provider.getRates(shipmentId, currency),
                null
        );
    }

    public boolean eligibleForChowdeck(ShipmentRequest request) {
        if (request == null || request.pickupAddress() == null || request.deliveryAddress() == null) {
            return false;
        }
        ShipmentAddress pickup = request.pickupAddress();
        ShipmentAddress delivery = request.deliveryAddress();
        if (!nigeria(pickup.country()) || !nigeria(delivery.country())) {
            return false;
        }
        String pickupState = state(pickup.state());
        String deliveryState = state(delivery.state());
        if (pickupState == null || !pickupState.equals(deliveryState)) {
            return false;
        }
        if (STATE_WIDE_ZONES.contains(pickupState)) {
            return true;
        }
        return switch (pickupState) {
            case "OYO" -> city(pickup.city()).equals("IBADAN")
                    && city(delivery.city()).equals("IBADAN");
            case "RIVERS" -> city(pickup.city()).equals("PORT HARCOURT")
                    && city(delivery.city()).equals("PORT HARCOURT");
            default -> false;
        };
    }

    private PreparedShipment prepareWithDefault(ShipmentRequest request, String currency) {
        ShippingProvider provider = registry.defaultProvider();
        var shipment = provider.createQuickShipment(request);
        return new PreparedShipment(
                registry.defaultProviderCode(),
                new ShippingProvider.ShippingPreparation(
                        shipment,
                        new ShippingProvider.ShippingQuoteResult(
                                provider.getRates(shipment.shipmentId(), currency),
                                null
                        )
                )
        );
    }

    private void shadowQuote(ShipmentRequest request, String currency) {
        try {
            ShippingProvider.ShippingQuoteResult result = registry.chowdeckProvider()
                    .quoteShipment(request.orderReference(), request, currency);
            log.info("Chowdeck shadow quote completed: reference={}, rates={}",
                    request.orderReference(), result.rates().size());
        } catch (RuntimeException exception) {
            log.warn("Chowdeck shadow quote failed: reference={}", request.orderReference());
        }
    }

    private boolean nigeria(String country) {
        String value = normalized(country);
        return "NG".equals(value) || "NIGERIA".equals(value);
    }

    private String state(String value) {
        String normalized = normalized(value);
        if (normalized == null) {
            return null;
        }
        if (Set.of("ABUJA", "FCT", "ABUJA FCT").contains(normalized)
                || normalized.startsWith("FEDERAL CAPITAL TERRITORY")) {
            return "FCT";
        }
        if (Set.of("RIVER", "RIVERS STATE").contains(normalized)) {
            return "RIVERS";
        }
        if (normalized.endsWith(" STATE")) {
            return normalized.substring(0, normalized.length() - " STATE".length());
        }
        return normalized;
    }

    private String city(String value) {
        String normalized = normalized(value);
        if ("PORTHARCOURT".equals(normalized)) {
            return "PORT HARCOURT";
        }
        return normalized == null ? "" : normalized;
    }

    private String normalized(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .trim()
                .replaceAll("[^A-Za-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .toUpperCase(Locale.ROOT);
    }

    public record PreparedShipment(
            String providerCode,
            ShippingProvider.ShippingPreparation preparation
    ) {
    }
}
