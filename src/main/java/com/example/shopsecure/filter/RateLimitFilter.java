package com.example.shopsecure.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    // Caffeine cache — auto-evicts idle IPs after 1 hour, caps at 10k entries
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .maximumSize(10_000)
            .build();

    // Global bucket — 50 req/sec across all users
    private final Bucket globalBucket = Bucket.builder()
            .addLimit(Bandwidth.classic(
                    100,
                    Refill.greedy(50, Duration.ofSeconds(1))
            ))
            .build();

    private Bucket createLeakyBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.classic(
                        10,                                       // allow small burst
                        Refill.greedy(1, Duration.ofMillis(500)) // 2 req/sec steady
                ))
                .build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator")
                || path.startsWith("/public")
                || path.equals("/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        // 1. Check global bucket first
        if (!globalBucket.tryConsume(1)) {
            log.warn("Global rate limit hit");
            sendTooManyRequests(response, "Server is busy. Try again shortly.", 5);
            return;
        }

        // 2. Resolve key — prefer authenticated user ID, fall back to IP
        String userId = (String) request.getAttribute("authenticatedUserId");
        String ip = resolveIp(request);
        String bucketKey = userId != null ? "user:" + userId : "ip:" + ip;

        // 3. Check per-key bucket
        Bucket bucket = buckets.get(bucketKey, k -> createLeakyBucket());

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            log.warn("Per-key rate limit hit for key={}", bucketKey);
            sendTooManyRequests(response, "Too many requests. Please slow down.", 3);
        }
    }

    private String resolveIp(HttpServletRequest request) {
        // X-Forwarded-For is set by Render's load balancer
        return Optional.ofNullable(request.getHeader("X-Forwarded-For"))
                .map(h -> h.split(",")[0].trim())
                .orElse(request.getRemoteAddr());
    }

    private void sendTooManyRequests(HttpServletResponse response,
                                     String message,
                                     int retryAfterSeconds) throws IOException {
        response.setStatus(429);
        response.setContentType("application/json");
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.getWriter().write("{\"error\": \"" + message + "\"}");
    }
}