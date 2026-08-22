package com.example.shopsecure.service;

import com.example.shopsecure.dto.response.ShippingCityResponse;
import com.example.shopsecure.dto.response.ShippingStateResponse;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.ServiceUnavailableException;
import com.example.shopsecure.provider.shipping.ShippingCity;
import com.example.shopsecure.provider.shipping.ShippingProvider;
import com.example.shopsecure.provider.shipping.ShippingState;
import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executor;

@Service
@Slf4j
public class ShippingLocationService {

    private static final String COUNTRY_CODE = "NG";

    private final ShippingProvider shippingProvider;
    private final LoadingCache<String, LocationCatalog> catalogs;

    @Autowired
    public ShippingLocationService(ShippingProvider shippingProvider) {
        this(
                shippingProvider,
                Duration.ofHours(24),
                Duration.ofMinutes(1),
                Ticker.systemTicker(),
                null
        );
    }

    ShippingLocationService(
            ShippingProvider shippingProvider,
            Duration refreshAfter,
            Duration retryAfter,
            Ticker ticker,
            Executor executor
    ) {
        this.shippingProvider = shippingProvider;
        Caffeine<String, LocationCatalog> cacheBuilder = Caffeine.newBuilder()
                .maximumSize(1)
                .refreshAfterWrite(refreshAfter)
                .ticker(ticker)
                .expireAfter(new Expiry<String, LocationCatalog>() {
                    @Override
                    public long expireAfterCreate(
                            String key,
                            LocationCatalog value,
                            long currentTime
                    ) {
                        return value.available() ? Long.MAX_VALUE : retryAfter.toNanos();
                    }

                    @Override
                    public long expireAfterUpdate(
                            String key,
                            LocationCatalog value,
                            long currentTime,
                            long currentDuration
                    ) {
                        return value.available() ? Long.MAX_VALUE : retryAfter.toNanos();
                    }

                    @Override
                    public long expireAfterRead(
                            String key,
                            LocationCatalog value,
                            long currentTime,
                            long currentDuration
                    ) {
                        return currentDuration;
                    }
                });
        if (executor != null) {
            cacheBuilder.executor(executor);
        }
        this.catalogs = cacheBuilder.build(new CacheLoader<>() {
            @Override
            public LocationCatalog load(String countryCode) {
                return loadInitialCatalog(countryCode);
            }

            @Override
            public LocationCatalog reload(String countryCode, LocationCatalog oldCatalog) {
                return refreshCatalog(countryCode, oldCatalog);
            }
        });
    }

    public List<ShippingStateResponse> getStates() {
        return catalog().states();
    }

    public List<ShippingCityResponse> getCities(String stateCode) {
        String normalizedState = normalizeStateCode(stateCode);
        LocationCatalog catalog = catalog();
        if (!catalog.citiesByState().containsKey(normalizedState)) {
            throw new BusinessException("Selected state is not supported");
        }
        return catalog.citiesByState().get(normalizedState);
    }

    private LocationCatalog catalog() {
        LocationCatalog catalog = catalogs.get(COUNTRY_CODE);
        if (!catalog.available()) {
            throw new ServiceUnavailableException("Shipping locations are currently unavailable");
        }
        return catalog;
    }

    private LocationCatalog loadInitialCatalog(String countryCode) {
        try {
            return loadCatalog(countryCode);
        } catch (RuntimeException exception) {
            log.warn("Could not load Nigerian shipping locations; retrying after cooldown");
            return LocationCatalog.unavailable();
        }
    }

    private LocationCatalog refreshCatalog(String countryCode, LocationCatalog oldCatalog) {
        try {
            return loadCatalog(countryCode);
        } catch (RuntimeException exception) {
            log.warn("Could not refresh Nigerian shipping locations; keeping cached catalog");
            return oldCatalog;
        }
    }

    private LocationCatalog loadCatalog(String countryCode) {
        log.info("Refreshing Nigerian shipping location catalog");
        List<ShippingState> providerStates = shippingProvider.getStates(countryCode);
        List<ShippingCity> providerCities = shippingProvider.getCities(countryCode);
        if (providerStates == null || providerStates.isEmpty()
                || providerCities == null || providerCities.isEmpty()) {
            throw new BusinessException("Shipping locations are currently unavailable");
        }

        Map<String, ShippingStateResponse> statesByCode = new LinkedHashMap<>();
        providerStates.stream()
                .filter(state -> state != null && countryCode.equalsIgnoreCase(state.countryCode()))
                .sorted(Comparator.comparing(
                        state -> requiredName(state.name()),
                        String.CASE_INSENSITIVE_ORDER
                ))
                .forEach(state -> statesByCode.putIfAbsent(
                        normalizeProviderCode(state.code()),
                        new ShippingStateResponse(normalizeProviderCode(state.code()), requiredName(state.name()))
                ));
        if (statesByCode.isEmpty()) {
            throw new BusinessException("Shipping locations are currently unavailable");
        }

        Map<String, Map<String, ShippingCityResponse>> uniqueCities = new TreeMap<>();
        statesByCode.keySet().forEach(code -> uniqueCities.put(code, new TreeMap<>(
                String.CASE_INSENSITIVE_ORDER
        )));
        providerCities.stream()
                .filter(city -> city != null && countryCode.equalsIgnoreCase(city.countryCode()))
                .forEach(city -> {
                    String stateCode = normalizeProviderCode(city.stateCode());
                    Map<String, ShippingCityResponse> stateCities = uniqueCities.get(stateCode);
                    if (stateCities != null) {
                        String name = requiredName(city.name());
                        stateCities.putIfAbsent(name, new ShippingCityResponse(name, stateCode));
                    }
                });

        Map<String, List<ShippingCityResponse>> citiesByState = new TreeMap<>();
        uniqueCities.forEach((code, cities) -> citiesByState.put(
                code,
                List.copyOf(new ArrayList<>(cities.values()))
        ));
        if (citiesByState.values().stream().anyMatch(List::isEmpty)) {
            throw new BusinessException("Shipping locations are currently unavailable");
        }
        return new LocationCatalog(
                List.copyOf(statesByCode.values()),
                Map.copyOf(citiesByState),
                true
        );
    }

    private String normalizeStateCode(String stateCode) {
        if (stateCode == null || stateCode.isBlank()) {
            throw new BusinessException("State code is required");
        }
        String normalized = stateCode.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9-]{1,10}")) {
            throw new BusinessException("State code is invalid");
        }
        return normalized;
    }

    private String normalizeProviderCode(String code) {
        if (code == null || code.isBlank()) {
            throw new BusinessException("Shipping locations are currently unavailable");
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9-]{1,10}")) {
            throw new BusinessException("Shipping locations are currently unavailable");
        }
        return normalized;
    }

    private String requiredName(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException("Shipping locations are currently unavailable");
        }
        return name.trim();
    }

    private record LocationCatalog(
            List<ShippingStateResponse> states,
            Map<String, List<ShippingCityResponse>> citiesByState,
            boolean available
    ) {
        private static LocationCatalog unavailable() {
            return new LocationCatalog(List.of(), Map.of(), false);
        }
    }
}
