package com.example.shopsecure.provider.shipping;

import com.example.shopsecure.provider.common.WebhookPayload;

import java.util.List;
import java.math.BigDecimal;
import java.time.Instant;

public interface ShippingProvider {
    default ShippingPreparation prepareShipment(ShipmentRequest request, String currency) {
        ShipmentResponse shipment = createQuickShipment(request);
        return new ShippingPreparation(
                shipment,
                new ShippingQuoteResult(getRates(shipment.shipmentId(), currency), null)
        );
    }

    default ShippingQuoteResult quoteShipment(
            String shipmentId,
            ShipmentRequest request,
            String currency
    ) {
        return new ShippingQuoteResult(getRates(shipmentId, currency), null);
    }

    AddressValidationResult validateAddress(ShipmentAddress address);

    List<ShippingState> getStates(String countryCode);

    List<ShippingCity> getCities(String countryCode);

    ShipmentResponse createQuickShipment(ShipmentRequest request);

    List<ShippingRateOption> getRates(String shipmentId, String currency);

    BigDecimal getWalletBalance();

    ShipmentResponse arrangePickup(String shipmentId, String rateId);

    default ShipmentResponse arrangePickup(
            String shipmentId,
            String rateId,
            ShipmentRequest request
    ) {
        return arrangePickup(shipmentId, rateId);
    }

    default boolean requiresShipmentRequestForBooking() {
        return false;
    }

    default boolean supportsPreBookingTracking() {
        return true;
    }

    default boolean draftExistsRemotely() {
        return true;
    }

    ShipmentTrackingStatus trackShipment(String shipmentId);

    ShipmentResponse cancelShipment(String shipmentId);

    default ShipmentResponse cancelShipment(String shipmentId, String reason) {
        return cancelShipment(shipmentId);
    }

    ShipmentStatusUpdatedEvent handleWebhookEvent(WebhookPayload payload);

    record ShippingPreparation(
            ShipmentResponse shipment,
            ShippingQuoteResult quoteResult
    ) {
    }

    record ShippingQuoteResult(
            List<ShippingRateOption> rates,
            Instant expiresAt
    ) {
    }
}
