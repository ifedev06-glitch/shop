package com.example.shopsecure.repository;

import com.example.shopsecure.entity.GuestOrderAccessToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GuestOrderAccessTokenRepository extends JpaRepository<GuestOrderAccessToken, Long> {
    Optional<GuestOrderAccessToken> findByTokenHash(String tokenHash);

    List<GuestOrderAccessToken> findByOrder_IdAndRevokedAtIsNull(Long orderId);
}
