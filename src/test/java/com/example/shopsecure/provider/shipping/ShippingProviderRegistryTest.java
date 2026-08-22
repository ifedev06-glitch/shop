package com.example.shopsecure.provider.shipping;

import com.example.shopsecure.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ShippingProviderRegistryTest {

    @Test
    void dispatchesPersistedProviderWithoutConsultingFeatureMode() {
        ShippingProvider terminal = mock(ShippingProvider.class);
        ShippingProvider chowdeck = mock(ShippingProvider.class);
        ShippingProvider stub = mock(ShippingProvider.class);
        ShippingProviderRegistry registry = new ShippingProviderRegistry(
                terminal,
                ShippingProviderCode.TERMINAL_AFRICA,
                chowdeck,
                terminal,
                stub
        );

        assertThat(registry.require(ShippingProviderCode.CHOWDECK)).isSameAs(chowdeck);
        assertThat(registry.require(ShippingProviderCode.TERMINAL_AFRICA)).isSameAs(terminal);
        assertThat(registry.require(ShippingProviderCode.STUB)).isSameAs(stub);
    }

    @Test
    void neverRoutesHistoricalTerminalShipmentThroughStub() {
        ShippingProvider stub = mock(ShippingProvider.class);
        ShippingProviderRegistry registry = new ShippingProviderRegistry(
                stub,
                ShippingProviderCode.STUB,
                mock(ShippingProvider.class),
                null,
                stub
        );

        assertThatThrownBy(() -> registry.require(ShippingProviderCode.TERMINAL_AFRICA))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Terminal Africa shipping is not configured");
    }
}
