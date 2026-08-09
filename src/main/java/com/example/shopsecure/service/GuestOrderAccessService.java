package com.example.shopsecure.service;

import com.example.shopsecure.entity.GuestOrderAccessToken;
import com.example.shopsecure.entity.Order;
import com.example.shopsecure.entity.OrderCounterparty;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.repository.GuestOrderAccessTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class GuestOrderAccessService {

    private final GuestOrderAccessTokenRepository tokenRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${order.guest-token-ttl:PT168H}")
    private Duration tokenTtl;

    @Transactional
    public IssuedGuestToken issue(Order order, OrderCounterparty counterparty) {
        Instant now = Instant.now();
        tokenRepository.findByOrder_IdAndRevokedAtIsNull(order.getId()).forEach(token -> {
            token.setRevokedAt(now);
            tokenRepository.save(token);
        });
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = now.plus(tokenTtl);
        tokenRepository.save(GuestOrderAccessToken.builder()
                .order(order)
                .counterparty(counterparty)
                .tokenHash(hash(rawToken))
                .expiresAt(expiresAt)
                .createdAt(now)
                .build());
        return new IssuedGuestToken(rawToken, expiresAt);
    }

    @Transactional(readOnly = true)
    public GuestOrderAccessToken require(String shareToken, String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new AccessDeniedException("Guest order access token is required");
        }
        GuestOrderAccessToken token = tokenRepository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new AccessDeniedException("Guest order access token is invalid"));
        if (token.getRevokedAt() != null || !token.getExpiresAt().isAfter(Instant.now())) {
            throw new AccessDeniedException("Guest order access token has expired");
        }
        if (!MessageDigest.isEqual(
                token.getOrder().getShareToken().getBytes(StandardCharsets.UTF_8),
                shareToken.getBytes(StandardCharsets.UTF_8)
        )) {
            throw new AccessDeniedException("Guest token does not belong to this order");
        }
        return token;
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to secure guest token", exception);
        }
    }

    public record IssuedGuestToken(String token, Instant expiresAt) {
    }
}
