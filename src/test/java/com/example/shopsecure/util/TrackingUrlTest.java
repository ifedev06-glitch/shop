package com.example.shopsecure.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TrackingUrlTest {

    @Test
    void acceptsOnlyCredentialFreeHttpsUrls() {
        assertThat(TrackingUrl.normalizeHttps(" https://tracking.example.com/package/ABC "))
                .isEqualTo("https://tracking.example.com/package/ABC");
        assertThat(TrackingUrl.normalizeHttps("http://tracking.example.com/package/ABC")).isNull();
        assertThat(TrackingUrl.normalizeHttps("javascript:alert(1)")).isNull();
        assertThat(TrackingUrl.normalizeHttps("https://user:secret@tracking.example.com/ABC")).isNull();
        assertThat(TrackingUrl.normalizeHttps("not a URL")).isNull();
    }
}
