package com.example.shopsecure.repository;

import com.example.shopsecure.entity.CheckoutLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CheckoutLinkRepository
        extends JpaRepository<CheckoutLink, Long> {

    Optional<CheckoutLink> findBySlug(String slug);
}
