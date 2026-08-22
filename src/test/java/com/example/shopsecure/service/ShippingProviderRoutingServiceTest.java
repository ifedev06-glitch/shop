package com.example.shopsecure.service;

import com.example.shopsecure.entity.ChowdeckMode;
import com.example.shopsecure.provider.shipping.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShippingProviderRoutingServiceTest {

    private ShippingProviderRegistry registry;
    private ChowdeckControlService controlService;
    private ShippingProvider defaultProvider;
    private ShippingProvider chowdeckProvider;
    private ShippingProviderRoutingService service;

    @BeforeEach
    void setUp() {
        registry = mock(ShippingProviderRegistry.class);
        controlService = mock(ChowdeckControlService.class);
        defaultProvider = mock(ShippingProvider.class);
        chowdeckProvider = mock(ShippingProvider.class);
        service = new ShippingProviderRoutingService(registry, controlService);
        when(registry.defaultProvider()).thenReturn(defaultProvider);
        when(registry.defaultProviderCode()).thenReturn(ShippingProviderCode.TERMINAL_AFRICA);
        when(registry.chowdeckProvider()).thenReturn(chowdeckProvider);
    }

    @Test
    void selectsChowdeckOnlyForEnabledEligibleSameStateRoute() {
        when(controlService.decision()).thenReturn(decision(ChowdeckMode.ON));
        ShippingProvider.ShippingPreparation preparation = preparation("order-1");
        when(chowdeckProvider.prepareShipment(any(), eq("NGN"))).thenReturn(preparation);

        ShippingProviderRoutingService.PreparedShipment result = service.prepare(
                request("Lagos", "Ikeja", "Lagos", "Lekki"),
                "NGN"
        );

        assertThat(result.providerCode()).isEqualTo(ShippingProviderCode.CHOWDECK);
        assertThat(result.preparation()).isSameAs(preparation);
        verify(defaultProvider, never()).createQuickShipment(any());
    }

    @Test
    void keepsInterstateRouteOnTerminalEvenWhenChowdeckIsOn() {
        when(controlService.decision()).thenReturn(decision(ChowdeckMode.ON));
        ShippingProvider.ShippingPreparation preparation = preparation("terminal-1");
        stubDefaultPreparation(preparation);

        ShippingProviderRoutingService.PreparedShipment result = service.prepare(
                request("Lagos", "Ikeja", "FCT", "Abuja"),
                "NGN"
        );

        assertThat(result.providerCode()).isEqualTo(ShippingProviderCode.TERMINAL_AFRICA);
        verify(chowdeckProvider, never()).prepareShipment(any(), any());
    }

    @Test
    void offModeMakesNoChowdeckCallForEligibleRoute() {
        when(controlService.decision()).thenReturn(decision(ChowdeckMode.OFF));
        stubDefaultPreparation(preparation("terminal-1"));

        ShippingProviderRoutingService.PreparedShipment result = service.prepare(
                request("Lagos", "Ikeja", "Lagos", "Lekki"),
                "NGN"
        );

        assertThat(result.providerCode()).isEqualTo(ShippingProviderCode.TERMINAL_AFRICA);
        verify(chowdeckProvider, never()).prepareShipment(any(), any());
        verify(chowdeckProvider, never()).quoteShipment(any(), any(), any());
    }

    @Test
    void restrictsOyoAndRiversToConfiguredCities() {
        assertThat(service.eligibleForChowdeck(request("Oyo", "Ibadan", "Oyo", "Ibadan")))
                .isTrue();
        assertThat(service.eligibleForChowdeck(request("Oyo", "Ogbomosho", "Oyo", "Ibadan")))
                .isFalse();
        assertThat(service.eligibleForChowdeck(request(
                "Rivers State",
                "Port-Harcourt",
                "Rivers",
                "Portharcourt"
        ))).isTrue();
        assertThat(service.eligibleForChowdeck(request(
                "Federal Capital Territory (FCT)",
                "Abuja",
                "Abuja FCT",
                "Abuja"
        ))).isTrue();
    }

    @Test
    void shadowCallsOnlyChowdeckFeeAndReturnsTerminalPreparation() {
        when(controlService.decision()).thenReturn(decision(ChowdeckMode.SHADOW));
        when(chowdeckProvider.quoteShipment(any(), any(), eq("NGN")))
                .thenReturn(new ShippingProvider.ShippingQuoteResult(List.of(), Instant.now()));
        stubDefaultPreparation(preparation("terminal-1"));

        ShippingProviderRoutingService.PreparedShipment result = service.prepare(
                request("Lagos", "Ikeja", "Lagos", "Lekki"),
                "NGN"
        );

        assertThat(result.providerCode()).isEqualTo(ShippingProviderCode.TERMINAL_AFRICA);
        verify(chowdeckProvider).quoteShipment(eq("order-1"), any(), eq("NGN"));
        verify(chowdeckProvider, never()).prepareShipment(any(), any());
    }

    @Test
    void fallsBackBeforePersistingWhenChowdeckQuoteFails() {
        when(controlService.decision()).thenReturn(decision(ChowdeckMode.ON));
        when(chowdeckProvider.prepareShipment(any(), eq("NGN")))
                .thenThrow(new IllegalStateException("provider unavailable"));
        stubDefaultPreparation(preparation("terminal-1"));

        ShippingProviderRoutingService.PreparedShipment result = service.prepare(
                request("Lagos", "Ikeja", "Lagos", "Lekki"),
                "NGN"
        );

        assertThat(result.providerCode()).isEqualTo(ShippingProviderCode.TERMINAL_AFRICA);
    }

    private ChowdeckControlService.Decision decision(ChowdeckMode mode) {
        return new ChowdeckControlService.Decision(mode, mode, false, 1);
    }

    private ShippingProvider.ShippingPreparation preparation(String shipmentId) {
        return new ShippingProvider.ShippingPreparation(
                new ShipmentResponse(
                        shipmentId,
                        ShipmentStatus.DRAFT,
                        null,
                        null,
                        Instant.now()
                ),
                new ShippingProvider.ShippingQuoteResult(List.of(), Instant.now())
        );
    }

    private void stubDefaultPreparation(ShippingProvider.ShippingPreparation preparation) {
        when(defaultProvider.createQuickShipment(any())).thenReturn(preparation.shipment());
        when(defaultProvider.getRates(preparation.shipment().shipmentId(), "NGN"))
                .thenReturn(preparation.quoteResult().rates());
    }

    private ShipmentRequest request(
            String pickupState,
            String pickupCity,
            String deliveryState,
            String deliveryCity
    ) {
        return new ShipmentRequest(
                new ShipmentAddress("1 Pickup Street", null, pickupCity, pickupState, "NG", "100001"),
                new ShipmentAddress("2 Delivery Road", null, deliveryCity, deliveryState, "NG", "100002"),
                new ParcelDetails(
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        "Parcel",
                        "Parcel",
                        new BigDecimal("1000.00"),
                        "NGN",
                        1,
                        "parcel"
                ),
                new ShipmentContact("Ada", "Vendor", "ada@example.com", "+2348011111111"),
                new ShipmentContact("Tobi", "Buyer", "tobi@example.com", "+2348022222222"),
                "order-1"
        );
    }
}
