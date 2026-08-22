package com.example.shopsecure.util;

import java.net.URI;

public final class TrackingUrl {

    private TrackingUrl() {
    }

    public static String normalizeHttps(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null) {
                return null;
            }
            return uri.toASCIIString();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
