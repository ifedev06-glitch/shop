package com.example.shopsecure.provider.shipping;

import com.example.shopsecure.exception.BusinessException;

import java.util.Locale;
import java.util.Objects;

public class ShippingProviderRegistry {

    private final ShippingProvider defaultProvider;
    private final String defaultProviderCode;
    private final ShippingProvider chowdeckProvider;
    private final ShippingProvider terminalAfricaProvider;
    private final ShippingProvider stubProvider;

    public ShippingProviderRegistry(
            ShippingProvider defaultProvider,
            String defaultProviderCode,
            ShippingProvider chowdeckProvider,
            ShippingProvider terminalAfricaProvider,
            ShippingProvider stubProvider
    ) {
        this.defaultProvider = Objects.requireNonNull(defaultProvider, "defaultProvider");
        this.defaultProviderCode = Objects.requireNonNull(defaultProviderCode, "defaultProviderCode")
                .trim()
                .toUpperCase(Locale.ROOT);
        this.chowdeckProvider = Objects.requireNonNull(chowdeckProvider, "chowdeckProvider");
        this.terminalAfricaProvider = terminalAfricaProvider;
        this.stubProvider = Objects.requireNonNull(stubProvider, "stubProvider");
    }

    public ShippingProvider defaultProvider() {
        return defaultProvider;
    }

    public String defaultProviderCode() {
        return defaultProviderCode;
    }

    public ShippingProvider chowdeckProvider() {
        return chowdeckProvider;
    }

    public ShippingProvider require(String providerCode) {
        if (providerCode == null || providerCode.isBlank()) {
            throw new BusinessException("Shipment provider is not configured");
        }
        String normalized = providerCode.trim().toUpperCase(Locale.ROOT);
        if (normalized.equals(defaultProviderCode) || "TEST".equals(normalized)) {
            return defaultProvider;
        }
        return switch (normalized) {
            case ShippingProviderCode.CHOWDECK -> chowdeckProvider;
            case ShippingProviderCode.TERMINAL_AFRICA -> requiredTerminalAfricaProvider();
            case ShippingProviderCode.STUB -> stubProvider;
            default -> throw new BusinessException("Shipment provider is not supported");
        };
    }

    private ShippingProvider requiredTerminalAfricaProvider() {
        if (terminalAfricaProvider == null) {
            throw new BusinessException("Terminal Africa shipping is not configured");
        }
        return terminalAfricaProvider;
    }
}
