package com.example.shopsecure.service;

import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.ServiceUnavailableException;
import com.example.shopsecure.provider.shipping.ShippingCity;
import com.example.shopsecure.provider.shipping.ShippingProvider;
import com.example.shopsecure.provider.shipping.ShippingState;
import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShippingLocationServiceTest {

    @Test
    void normalizesSortsAndCachesOneCatalogForAllRequests() {
        ShippingProvider provider = mock(ShippingProvider.class);
        when(provider.getStates("NG")).thenReturn(List.of(
                new ShippingState("Rivers", "ri", "NG"),
                new ShippingState("Lagos", "la", "NG"),
                new ShippingState("Federal Capital Territory", "fc", "NG")
        ));
        when(provider.getCities("NG")).thenReturn(List.of(
                new ShippingCity("Ikeja", "la", "NG"),
                new ShippingCity("epe", "LA", "NG"),
                new ShippingCity("EPE", "LA", "NG"),
                new ShippingCity("Abuja", "FC", "NG"),
                new ShippingCity("Port Harcourt", "RI", "NG")
        ));
        ShippingLocationService service = new ShippingLocationService(provider);

        assertThat(service.getStates())
                .extracting(state -> state.code() + ":" + state.name())
                .containsExactly(
                        "FC:Federal Capital Territory",
                        "LA:Lagos",
                        "RI:Rivers"
                );
        assertThat(service.getCities(" la "))
                .extracting(city -> city.name() + ":" + city.stateCode())
                .containsExactly("epe:LA", "Ikeja:LA");
        assertThat(service.getCities("FC"))
                .extracting(city -> city.name())
                .containsExactly("Abuja");
        assertThatThrownBy(() -> service.getCities("XX"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Selected state is not supported");

        verify(provider).getStates("NG");
        verify(provider).getCities("NG");
    }

    @Test
    void keepsLastSuccessfulCatalogWhenRefreshFails() {
        ShippingProvider provider = mock(ShippingProvider.class);
        when(provider.getStates("NG"))
                .thenReturn(List.of(new ShippingState("Lagos", "LA", "NG")))
                .thenThrow(new BusinessException("Terminal Africa request failed"));
        when(provider.getCities("NG")).thenReturn(List.of(
                new ShippingCity("Ikeja", "LA", "NG")
        ));
        MutableTicker ticker = new MutableTicker();
        ShippingLocationService service = new ShippingLocationService(
                provider,
                Duration.ofHours(24),
                Duration.ofMinutes(1),
                ticker,
                Runnable::run
        );

        assertThat(service.getStates()).extracting(state -> state.code()).containsExactly("LA");
        ticker.advance(Duration.ofHours(25));
        assertThat(service.getStates()).extracting(state -> state.code()).containsExactly("LA");

        verify(provider, times(2)).getStates("NG");
        verify(provider).getCities("NG");
    }

    @Test
    void rejectsUnusableCatalogAndAppliesInitialFailureCooldown() {
        ShippingProvider provider = mock(ShippingProvider.class);
        when(provider.getStates("NG")).thenReturn(List.of(
                new ShippingState("Lagos", "LA", "NG")
        ));
        when(provider.getCities("NG")).thenReturn(List.of(
                new ShippingCity("Unknown city", "XX", "NG")
        ));
        MutableTicker ticker = new MutableTicker();
        ShippingLocationService service = new ShippingLocationService(
                provider,
                Duration.ofHours(24),
                Duration.ofMinutes(1),
                ticker,
                Runnable::run
        );

        assertThatThrownBy(service::getStates)
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessage("Shipping locations are currently unavailable");
        assertThatThrownBy(service::getStates)
                .isInstanceOf(ServiceUnavailableException.class);
        verify(provider).getStates("NG");
        verify(provider).getCities("NG");

        ticker.advance(Duration.ofMinutes(2));
        assertThatThrownBy(service::getStates)
                .isInstanceOf(ServiceUnavailableException.class);
        verify(provider, times(2)).getStates("NG");
        verify(provider, times(2)).getCities("NG");
    }

    private static final class MutableTicker implements Ticker {
        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long read() {
            return nanos.get();
        }

        private void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }
}
