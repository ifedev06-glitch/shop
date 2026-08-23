package com.example.shopsecure.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    @Test
    void signedProviderWebhooksRemainExempt() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true);

        for (int attempt = 0; attempt < 30; attempt++) {
            MockHttpServletResponse response = invoke(
                    filter,
                    "/api/webhooks/terminal",
                    "198.51.100.10",
                    null,
                    null
            );
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void unsignedChowdeckWebhookIsRateLimitedByIpEvenForAuthenticatedCallers() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true);
        List<MockHttpServletResponse> responses = new ArrayList<>();

        for (int attempt = 0; attempt < 30; attempt++) {
            responses.add(invoke(
                    filter,
                    "/api/webhooks/chowdeck",
                    "198.51.100.20",
                    "203.0.113." + attempt,
                    "user-" + attempt
            ));
        }

        MockHttpServletResponse limited = responses.stream()
                .filter(response -> response.getStatus() == 429)
                .findFirst()
                .orElseThrow();
        assertThat(limited.getHeader("Retry-After")).isEqualTo("3");
        assertThat(limited.getContentAsString()).contains("Too many requests");
    }

    @Test
    void anonymousDisputeSubmissionsHaveAStrictPerIpLimit() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true);

        for (int orderId = 1; orderId <= 5; orderId++) {
            assertThat(invoke(
                    filter,
                    "/orders/" + orderId + "/disputes",
                    "198.51.100.30",
                    null,
                    null
            ).getStatus()).isEqualTo(200);
        }

        MockHttpServletResponse limited = invoke(
                filter,
                "/orders/6/disputes",
                "198.51.100.30",
                null,
                null
        );
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isEqualTo("3600");
        assertThat(limited.getContentAsString()).contains("Too many dispute requests");
    }

    @Test
    void disputeOrderLimitAppliesAcrossSourceIps() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true);

        assertThat(invoke(filter, "/orders/42/disputes", "198.51.100.31", null, null).getStatus())
                .isEqualTo(200);
        assertThat(invoke(filter, "/orders/42/disputes", "198.51.100.32", null, null).getStatus())
                .isEqualTo(200);

        MockHttpServletResponse limited = invoke(
                filter,
                "/orders/42/disputes",
                "198.51.100.33",
                null,
                null
        );
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isEqualTo("3600");
    }

    @Test
    void oversizedDisputeRequestIsRejectedBeforeTheFilterChain() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/orders/42/disputes");
        request.setRemoteAddr("198.51.100.34");
        request.setContent(new byte[16_385]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("Dispute request is too large");
    }

    private MockHttpServletResponse invoke(
            RateLimitFilter filter,
            String path,
            String ip,
            String forwardedIp,
            String userId
    ) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(ip);
        if (forwardedIp != null) {
            request.addHeader("X-Forwarded-For", forwardedIp);
        }
        if (userId != null) {
            request.setAttribute("authenticatedUserId", userId);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
